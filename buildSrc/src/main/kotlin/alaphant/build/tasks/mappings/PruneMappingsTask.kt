package alaphant.build.tasks.mappings

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class PruneMappingsTask : DefaultTask() {
    // make gradle skip when there is nothing to clean up
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val unconsumable: ConfigurableFileCollection

    /** The store root, as the stopping point for tidying up emptied package directories. */
    @get:Internal
    abstract val namedDir: DirectoryProperty

    @TaskAction
    fun prune() {
        val files = unconsumable.files.sortedBy { it.path }
        val deleted = files.filter { file ->
            file.delete().also { ok ->
                if (ok) logger.lifecycle("Deleted ${file.path} (no intermediary element behind it)")
                else logger.warn("Could not delete ${file.path}")
            }
        }

        val root = namedDir.get().asFile.canonicalFile
        for (start in deleted.mapNotNull { it.parentFile }.distinct()) {
            var current: File? = start.canonicalFile
            while (current != null && current != root && current.startsWith(root) &&
                current.list()?.isEmpty() == true
            ) {
                if (!current.delete()) break
                current = current.parentFile
            }
        }

        logger.lifecycle("Pruned ${deleted.size} unconsumable mapping file(s)")
    }
}
