package alaphant.build.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Launches Charles from the assembled module path.
 */
abstract class CharlesRunTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modulePath: DirectoryProperty

    /** JVM options as declared by Charles' own Info.plist. */
    @get:Input
    abstract val jvmOptions: ListProperty<String>

    /** `module/class`, e.g. `com.charlesproxy/com.charlesproxy.main.macos.gui.Main`. */
    @get:Input
    abstract val mainModuleAndClass: Property<String>

    /** Directory holding Charles' native libraries (`Contents/MacOS` on macOS). */
    @get:Input
    @get:Optional
    abstract val nativeLibraryPath: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val agentJar: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val extraJvmArgs: ListProperty<String>

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:Internal
    abstract val javaLauncher: Property<JavaLauncher>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val main = mainModuleAndClass.get()
        val args = buildList {
            add("--module-path")
            add(modulePath.get().asFile.absolutePath)
            addAll(jvmOptions.get())
            nativeLibraryPath.orNull?.let { add("-Djava.library.path=$it") }
            agentJar.orNull?.let { add("-javaagent:${it.asFile.absolutePath}") }
            addAll(extraJvmArgs.getOrElse(emptyList()))
            add("--module")
            add(main)
        }

        logger.lifecycle("Launching $main")
        logger.info("java ${args.joinToString(" ")}")

        execOps.exec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            this.args = args
            workingDir(this@CharlesRunTask.workingDir.get().asFile)
        }
    }
}
