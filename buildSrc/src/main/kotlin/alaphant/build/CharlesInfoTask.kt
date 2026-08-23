package alaphant.build

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Diagnostic task: prints what the build resolved. Start here when something looks wrong.
 */
abstract class CharlesInfoTask : DefaultTask() {
    @get:Internal
    abstract val installDirPath: Property<String>

    @get:Internal
    abstract val charlesVersion: Property<String>

    @get:Internal
    abstract val libraryCount: Property<Int>

    @get:Internal
    abstract val intermediaryFilePath: Property<String>

    @get:Internal
    abstract val namedDirPath: Property<String>

    @get:Internal
    abstract val namedJarPath: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun report() {
        logger.lifecycle("Charles install : ${installDirPath.get()}")
        logger.lifecycle("Charles version : ${charlesVersion.get()}")
        logger.lifecycle("Dependency jars : ${libraryCount.get()}")

        val intermediary = File(intermediaryFilePath.get())
        logger.lifecycle(
            "Intermediary    : " + if (intermediary.isFile) {
                "${intermediary.path} (${intermediary.readLines().size} lines)"
            } else {
                "not generated yet -- run ./gradlew generateIntermediary"
            }
        )

        val named = File(namedDirPath.get())
        val namedFiles = named.walkTopDown().count { it.isFile && it.extension == "mapping" }
        logger.lifecycle("Named mappings  : ${named.path} ($namedFiles classes)")

        val jar = File(namedJarPath.get())
        logger.lifecycle(
            "Remapped jar    : " + if (jar.isFile) {
                "${jar.path} (${jar.length() / 1024} KiB)"
            } else {
                "not built yet -- run ./gradlew remapNamed"
            }
        )
    }
}
