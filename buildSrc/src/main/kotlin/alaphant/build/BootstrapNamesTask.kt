package alaphant.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.zip.ZipFile

/**
 * Recovers names the obfuscator failed to hide, before any agent time is spent.
 *
 * Nothing here guesses from a class' shape or its vibe — every pass has a concrete artefact behind
 * it, and every name it writes carries a `COMMENT` saying which one. Passes run in confidence order
 * and the first one to claim an element wins; a name already in the store, whoever wrote it, is
 * never overwritten.
 *
 *  1. **Lombok `@Slf4j` logger literals.** Charles compiles logger names to string constants holding
 *     the original fully-qualified class name, so an obfuscated class states its own name in its own
 *     constant pool. Zero ambiguity.
 *  2. **Inner-class file-name leaks.** The obfuscator renamed outer classes but left the inner class
 *     *files* named after the original outer — `com/charlesproxy/macos/MacOSImpl$2` is in the jar
 *     while `MacOSImpl` is not. The plan expected this to need constrained assignment per package,
 *     but it does not: the inner's synthetic `this$0` field is typed as the real enclosing class, so
 *     the link is direct. That turns 4.3 from "high confidence" into certain.
 *  3. **`module-info` `provides ... with ...`.** Names the *role* of a class rather than the class,
 *     so this pass writes evidence comments and, where the providers agree on a suffix, a `Maybe`
 *     name.
 *  4. **The Charles 4 corpus.** Same product, mostly the same class tree, but Charles 5 moved and
 *     renamed things — so these land as provisional `Maybe`/`maybe` names, matched on descriptor
 *     shape and required to be a mutual best fit within the package.
 *
 * The identity pass the plan lists as 4.1 has nothing to do: the intermediary scheme already passes
 * readable names through verbatim, so all 372 named outer classes and every readable member are
 * correct in the `named` namespace without a single mapping line.
 */
abstract class BootstrapNamesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val officialJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val intermediaryMappings: RegularFileProperty

    /** The Charles 4 naming corpus, tiny v1. Not authoritative — a source of candidates. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val legacyMappings: RegularFileProperty

    /**
     * Written in place, in the source tree. Deliberately not declared as a task output: this is a
     * generator you invoke when you want it, and `mergeMappings` consumes the directory as committed
     * content rather than as something the build produces.
     */
    @get:Internal
    abstract val namedDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun bootstrap() {
        val jar = Jar.read(officialJar.get().asFile)
        val intermediary = IntermediaryIndex.read(intermediaryMappings.get().asFile)
        val store = NamedStore.load(namedDir.get().asFile)

        logger.lifecycle("Bootstrapping names over ${jar.nodes.size} classes")
        val before = store.namedClassCount

        val recovery = Recovery(jar, intermediary, store, logger)
        recovery.loggerLiterals()
        recovery.innerClassLeaks()
        recovery.moduleServices()
        recovery.charles4Corpus(legacyMappings.get().asFile)

        store.write(namedDir.get().asFile)

        logger.lifecycle("")
        logger.lifecycle("Named ${store.namedClassCount - before} new classes (${store.namedClassCount} total), ${store.namedMemberCount} members")
        logger.lifecycle("Wrote ${namedDir.get().asFile}")
    }

}

/** The official jar, parsed once. */
internal class Jar(val nodes: List<ClassNode>, val moduleInfo: ByteArray?) {
    val byName: Map<String, ClassNode> = nodes.associateBy { it.name }

    companion object {
        fun read(file: File): Jar {
            var moduleInfo: ByteArray? = null
            val nodes = ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .mapNotNull { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == ModuleInfoRemapper.MODULE_INFO) {
                            moduleInfo = bytes
                            null
                        } else {
                            ClassNode().also { ClassReader(bytes).accept(it, 0) }
                        }
                    }
                    .sortedBy { it.name }
                    .toList()
            }
            return Jar(nodes, moduleInfo)
        }
    }
}
