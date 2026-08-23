package alaphant.build

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Reports how much of the mapping surface is actually named.
 *
 * Only elements still carrying a placeholder intermediary name (`class_812`, `method_3301`) count:
 * everything in the file has *some* intermediary name, and elements Charles never obfuscated are not
 * in the file at all.
 */
abstract class AnalyzeMappingsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    /** How many packages to break down individually. */
    @get:Input
    @get:Optional
    abstract val packageLimit: Property<Int>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun analyze() {
        val tree = MemoryMappingTree()
        MappingReader.read(mappings.get().asFile.toPath(), tree)

        val namespace = tree.dstNamespaces.indexOf(NAMED)
        require(namespace >= 0) {
            "No '$NAMED' namespace in ${mappings.get().asFile.name} " +
                "(have: ${tree.srcNamespace}, ${tree.dstNamespaces.joinToString(", ")})"
        }

        logger.lifecycle("${tree.srcNamespace} -> ${tree.dstNamespaces.joinToString(" -> ")}")
        logger.lifecycle("")

        val overall = Counts()
        val byPackage = LinkedHashMap<String, Counts>()

        for (cls in tree.classes) {
            val intermediary = cls.getDstName(0) ?: cls.srcName
            val counts = byPackage.getOrPut(intermediary.substringBeforeLast('/', "(default)")) { Counts() }

            for (target in listOf(overall, counts)) {
                target.record(Kind.CLASS, intermediary, cls.getDstName(namespace))
                for (field in cls.fields) {
                    target.record(Kind.FIELD, field.getDstName(0) ?: field.srcName, field.getDstName(namespace))
                }
                for (method in cls.methods) {
                    target.record(Kind.METHOD, method.getDstName(0) ?: method.srcName, method.getDstName(namespace))
                    for (arg in method.args) {
                        target.recordArg(arg.getDstName(namespace) != null)
                    }
                }
            }
        }

        logger.lifecycle("== overall ==")
        report(overall)

        val interesting = byPackage.entries
            .filter { it.value.placeholders > 0 }
            .sortedByDescending { it.value.placeholders }
            .take(packageLimit.getOrElse(DEFAULT_PACKAGE_LIMIT))

        if (interesting.isNotEmpty()) {
            logger.lifecycle("")
            logger.lifecycle("== packages with the most left to name ==")
            logger.lifecycle(String.format("%-52s %8s %8s %6s", "package", "named", "total", ""))
            for ((pkg, counts) in interesting) {
                val named = counts.named
                val total = counts.placeholders + named
                logger.lifecycle(String.format("%-52s %8d %8d %5.1f%%", pkg, named, total, percent(named, total)))
            }
            val remaining = byPackage.count { it.value.placeholders > 0 } - interesting.size
            if (remaining > 0) logger.lifecycle("... and $remaining more package(s) with work outstanding")
        }
    }

    private fun report(counts: Counts) {
        logger.lifecycle(String.format("%-9s %8s %8s %8s", "", "named", "to name", ""))
        for (kind in Kind.entries) {
            val named = counts.named(kind)
            val total = counts.total(kind)
            logger.lifecycle(
                String.format("%-9s %8d %8d %7.1f%%", kind.label, named, total - named, percent(named, total))
            )
        }
        if (counts.args > 0) {
            logger.lifecycle(String.format("%-9s %8d %8d %7.1f%%", "params", counts.namedArgs, counts.args - counts.namedArgs, percent(counts.namedArgs, counts.args)))
        }
    }

    private fun percent(part: Int, whole: Int) = if (whole == 0) 100.0 else part * 100.0 / whole

    private enum class Kind(val label: String, val prefix: String) {
        CLASS("classes", "class_"),
        FIELD("fields", "field_"),
        METHOD("methods", "method_"),
        ;

        /** `<prefix><digits>` as the last path segment: the allocation scheme, not a substring guess. */
        fun isPlaceholder(intermediary: String): Boolean {
            val simple = intermediary.substringAfterLast('/').substringAfterLast('$')
            return simple.startsWith(prefix) && simple.length > prefix.length &&
                simple.drop(prefix.length).all { it.isDigit() }
        }
    }

    private class Counts {
        private val namedPerKind = IntArray(Kind.entries.size)
        private val totalPerKind = IntArray(Kind.entries.size)
        var args = 0
            private set
        var namedArgs = 0
            private set

        fun record(kind: Kind, intermediary: String, namedName: String?) {
            if (!kind.isPlaceholder(intermediary)) return
            totalPerKind[kind.ordinal]++
            if (namedName != null) namedPerKind[kind.ordinal]++
        }

        fun recordArg(hasName: Boolean) {
            args++
            if (hasName) namedArgs++
        }

        fun named(kind: Kind) = namedPerKind[kind.ordinal]
        fun total(kind: Kind) = totalPerKind[kind.ordinal]

        val named: Int get() = namedPerKind.sum()
        val placeholders: Int get() = totalPerKind.sum() - namedPerKind.sum()
    }

    private companion object {
        const val NAMED = "named"
        const val DEFAULT_PACKAGE_LIMIT = 20
    }
}
