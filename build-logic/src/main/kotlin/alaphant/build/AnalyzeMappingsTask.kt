package alaphant.build

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Reports mapping coverage per namespace and kind */
abstract class AnalyzeMappingsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @TaskAction
    fun analyze() {
        val tree = MemoryMappingTree()
        MappingReader.read(mappings.get().asFile.toPath(), tree)

        val namespaces = tree.dstNamespaces
        logger.lifecycle("Source namespace: ${tree.srcNamespace}")
        logger.lifecycle("Target namespaces: ${namespaces.joinToString(", ")}")

        for ((index, namespace) in namespaces.withIndex()) {
            var classes = 0
            var mappedClasses = 0
            var fields = 0
            var mappedFields = 0
            var methods = 0
            var mappedMethods = 0

            for (cls in tree.classes) {
                classes++
                if (cls.getDstName(index) != null) mappedClasses++

                for (field in cls.fields) {
                    fields++
                    if (field.getDstName(index) != null) mappedFields++
                }
                for (method in cls.methods) {
                    methods++
                    if (method.getDstName(index) != null) mappedMethods++
                }
            }

            logger.lifecycle("")
            logger.lifecycle("== namespace '$namespace' ==")
            report("Classes", mappedClasses, classes)
            report("Fields", mappedFields, fields)
            report("Methods", mappedMethods, methods)
        }
    }

    private fun report(label: String, mapped: Int, total: Int) {
        val percent = if (total == 0) 0.0 else mapped * 100.0 / total
        logger.lifecycle(String.format("%-9s %6d / %-6d  %5.1f%%", label, mapped, total, percent))
    }
}
