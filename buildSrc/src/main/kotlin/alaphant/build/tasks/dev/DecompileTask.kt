package alaphant.build.tasks.dev

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Decompiles a jar to a tree of `.java` files with Vineflower.
 */
abstract class DecompileTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    /** Charles' dependency jars, passed to Vineflower so external types resolve. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraries: ConfigurableFileCollection

    @get:Classpath
    abstract val decompilerClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val fsOps: FileSystemOperations

    @get:Inject
    abstract val archiveOps: ArchiveOperations

    @TaskAction
    fun decompile() {
        val classesDir = temporaryDir.resolve("classes")
        val out = outputDir.get().asFile

        fsOps.delete { delete(classesDir, out) }
        fsOps.copy {
            from(archiveOps.zipTree(inputJar.get().asFile))
            into(classesDir)
        }
        out.mkdirs()

        val args = buildList {
            // keep generic signatures
            add("-dgs=1")
            // resolve against the current JDK's runtime image
            add("-jrt=1")
            add("-log=WARN")
            libraries.files.filter { it.isFile }.forEach { add("-e=${it.absolutePath}") }
            add(classesDir.absolutePath)
            add(out.absolutePath)
        }

        logger.lifecycle("Decompiling ${inputJar.get().asFile.name} -> $out")

        execOps.javaexec {
            classpath = decompilerClasspath
            mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")
            this.args = args
            maxHeapSize = "3G"
        }

        val count = out.walkTopDown().count { it.isFile && it.extension == "java" }
        logger.lifecycle("Decompiled $count source files")
    }
}
