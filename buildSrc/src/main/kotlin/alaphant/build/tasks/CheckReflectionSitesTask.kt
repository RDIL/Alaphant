package alaphant.build.tasks

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File

/**
 * Fails if Charles loads a class by name that the remap renames, and no mixin patches the literal.
 *
 * Remapping rewrites class names but not string constants, so `Class.forName("…vQtF")` looks for a
 * class that no longer exists — at runtime, far from the mapping change that caused it. 5.0.3 has
 * three such sites, all in `com/charlesproxy/macos` and all patched by one mixin; the job here is to
 * notice a fourth.
 *
 * Sites whose argument is computed rather than constant are reported but cannot be checked.
 */
abstract class CheckReflectionSitesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val officialJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    /** Literals a mixin is known to rewrite, one per line, `#` comments. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val handled: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val jar = Jar.read(officialJar.get().asFile)
        val renamed = renamedClasses()
        val allowed = readHandled()

        val sites = collectSites(jar)
        val unhandled = mutableListOf<Site>()
        var dynamic = 0
        var safe = 0

        for (site in sites) {
            val literal = site.literal
            if (literal == null) {
                dynamic++
                continue
            }
            val internal = literal.replace('.', '/')
            when {
                internal !in jar.byName -> safe++           // a JDK or library class; never remapped
                internal !in renamed -> safe++              // in the jar but the mappings leave it alone
                literal in allowed -> safe++
                else -> unhandled.add(site)
            }
        }

        logger.lifecycle("Load-by-name sites: ${sites.size} total, $safe safe, $dynamic computed at runtime, ${unhandled.size} unhandled")
        for (site in sites.filter { it.literal == null }) {
            logger.info("  computed: ${site.owner}#${site.method} via ${site.call}")
        }

        if (unhandled.isEmpty()) return

        val detail = unhandled.joinToString("\n") { site ->
            val internal = site.literal!!.replace('.', '/')
            "  \"${site.literal}\" -> ${renamed[internal]}\n" +
                "      loaded by ${site.owner}#${site.method} via ${site.call}"
        }
        throw GradleException(
            "${unhandled.size} class(es) are loaded by name but renamed by the mappings, so the lookup will " +
                "fail at runtime:\n$detail\n\n" +
                "Patch each site with an @ModifyArg mixin that substitutes the named class, then add the " +
                "literal to ${handled.orNull?.asFile?.name ?: "the handled-literals file"}."
        )
    }

    private data class Site(val literal: String?, val owner: String, val method: String, val call: String)

    private fun collectSites(jar: Jar): List<Site> = buildList {
        for (node in jar.nodes) {
            for (method in node.methods.orEmpty()) {
                val instructions = method.instructions ?: continue
                for (insn in instructions) {
                    if (insn !is MethodInsnNode) continue
                    val call = "${insn.owner}.${insn.name}"
                    if (call !in LOAD_BY_NAME) continue
                    add(Site(literalBefore(insn), node.name, method.name, call))
                }
            }
        }
    }

    /** The string constant pushed immediately before the call, if the argument is a literal at all. */
    private fun literalBefore(call: MethodInsnNode): String? {
        var previous: AbstractInsnNode? = call.previous
        while (previous != null) {
            if (previous is LdcInsnNode) return previous.cst as? String
            if (previous.opcode >= 0) return null
            previous = previous.previous
        }
        return null
    }

    /** official -> named, for classes the mappings actually rename. */
    private fun renamedClasses(): Map<String, String> {
        val tree = MemoryMappingTree()
        MappingReader.read(mappings.get().asFile.toPath(), MappingFormat.TINY_2_FILE, tree)
        val namespace = tree.dstNamespaces.indexOf("named")
        return tree.classes.mapNotNull { cls ->
            val named = cls.getDstName(namespace)?.takeIf { it != cls.srcName } ?: return@mapNotNull null
            cls.srcName to named
        }.toMap()
    }

    private fun readHandled(): Set<String> {
        val file: File = handled.orNull?.asFile ?: return emptySet()
        if (!file.isFile) return emptySet()
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private companion object {
        val LOAD_BY_NAME = setOf("java/lang/Class.forName", "java/lang/ClassLoader.loadClass")
    }
}
