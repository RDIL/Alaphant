package alaphant.build.tasks

import alaphant.build.mappings.ModuleInfoRemapper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.collections.iterator

/**
 * Opens the named mappings in the Enigma GUI, editing `intermediary -> named` against the
 * intermediary jar.
 *
 * Enigma reads and writes the same directory format the store uses, laying files out by *named* name
 * where one exists — the convention generated files follow, so saving over them leaves no duplicates.
 *
 * Two things are filed off Enigma's copy of the jar first:
 *
 *  - `module-info.class`: nothing mappable, and `ACC_MODULE` is needless risk for a tool that expects
 *    ordinary classes.
 *  - Synthetic methods sharing a descriptor with a differently-named ordinary method in the same
 *    class, which get Enigma stuck in a recursive cycle.
 */
abstract class EnigmaTask : DefaultTask() {
    @get:Classpath
    abstract val enigmaClasspath: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    /** Charles' dependency jars, so Enigma can resolve external supertypes. */
    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:Internal
    abstract val mappingsDir: DirectoryProperty

    @get:Internal
    abstract val javaLauncher: Property<JavaLauncher>

    @get:Inject
    abstract val execOps: ExecOperations

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun launch() {
        val mappings = mappingsDir.get().asFile.also { it.mkdirs() }
        val jar = prepareJar()

        logger.lifecycle("Opening Enigma on ${jar.name}")
        logger.lifecycle("  mappings: $mappings")
        logger.lifecycle("  Enigma saves back to that directory in the same format (File > Save Mappings).")

        execOps.javaexec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            classpath = enigmaClasspath
            mainClass.set(MAIN_CLASS)
            maxHeapSize = "4G"
            args = buildList {
                add("--jar")
                add(jar.absolutePath)
                add("--mappings")
                add(mappings.absolutePath)
                libraries.files.filter { it.isFile }.forEach {
                    add("--library")
                    add(it.absolutePath)
                }
            }
        }
    }

    /** Writes Enigma's copy of the jar, minus the two things it cannot cope with. See the class doc. */
    private fun prepareJar(): File {
        val source = inputJar.get().asFile
        val target = temporaryDir.resolve("enigma-input.jar")
        if (target.isFile && target.lastModified() >= source.lastModified()) return target

        var strippedMethods = 0
        ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    if (entry.name == ModuleInfoRemapper.MODULE_INFO) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.isDirectory) {
                        out.closeEntry()
                        continue
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name.endsWith(".class")) {
                        val doomed = shadowingSyntheticMethods(bytes)
                        if (doomed.isEmpty()) {
                            out.write(bytes)
                        } else {
                            strippedMethods += doomed.size
                            out.write(withoutMethods(bytes, doomed))
                        }
                    } else {
                        out.write(bytes)
                    }
                    out.closeEntry()
                }
            }
        }

        logger.info("Prepared Enigma input: dropped module-info.class and $strippedMethods synthetic method(s)")
        return target
    }

    private fun shadowingSyntheticMethods(bytes: ByteArray): Set<String> {
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_CODE)

        val ordinary = node.methods.orEmpty().filter { it.access and Opcodes.ACC_SYNTHETIC == 0 }
        return node.methods.orEmpty()
            .filter { method ->
                method.access and Opcodes.ACC_SYNTHETIC != 0 &&
                    ordinary.any { it.desc == method.desc && it.name != method.name }
            }
            .map { "${it.name}${it.desc}" }
            .toSet()
    }

    private fun withoutMethods(bytes: ByteArray, doomed: Set<String>): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if ("$name$descriptor" in doomed) null
                    else super.visitMethod(access, name, descriptor, signature, exceptions)
            },
            0,
        )
        return writer.toByteArray()
    }

    private companion object {
        const val MAIN_CLASS = "cuchaz.enigma.gui.Main"
    }
}
