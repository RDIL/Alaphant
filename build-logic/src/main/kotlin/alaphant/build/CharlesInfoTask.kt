package alaphant.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Diagnostic task: prints what the build detected.
 */
abstract class CharlesInfoTask : DefaultTask() {
    @get:Internal
    abstract val installDir: Property<String>

    @get:Internal
    abstract val charlesVersion: Property<String>

    @get:Internal
    abstract val libraryPaths: ListProperty<String>

    @get:Internal
    abstract val mappingFile: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun report() {
        val install = installDir.orNull
        logger.lifecycle("Charles install : ${install ?: "NOT FOUND"}")
        logger.lifecycle("Charles version : ${charlesVersion.getOrElse("unknown")}")

        if (install == null) {
            logger.lifecycle("")
            logger.lifecycle("No Charles install detected. Either install Charles 5, or set")
            logger.lifecycle("  alaphant.charlesInstall=/path/to/Contents/Java")
            logger.lifecycle("in gradle.properties, or drop charles.jar into target/.")
            return
        }

        val jar = File(install, "charles.jar")
        logger.lifecycle(
            "Charles jar     : " +
                if (jar.isFile) "${jar.name} (${jar.length() / 1024} KiB)" else "MISSING at ${jar.absolutePath}"
        )
        logger.lifecycle("Dependency jars : ${libraryPaths.get().size}")

        val mappings = mappingFile.orNull?.let(::File)
        logger.lifecycle(
            "Mappings        : " + when {
                mappings == null -> "<unset>"
                mappings.isFile -> "${mappings.path} (${mappings.readLines().size} lines)"
                else -> "MISSING at ${mappings.path}"
            }
        )
    }
}
