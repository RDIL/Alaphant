package alaphant.build.tasks

import alaphant.build.mappings.ModuleInfoRemapper
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.regex.Pattern

/** Remaps a jar across one namespace hop of the tiny v2 mappings */
@CacheableTask
abstract class RemapJarTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappings: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    /** Charles' own dependency jars, so inheritance across the module path resolves. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun remap() {
        val input = inputJar.get().asFile.toPath()
        val output = outputJar.get().asFile.toPath()
        val from = fromNamespace.get()
        val to = toNamespace.get()

        logger.lifecycle("Remapping ${input.fileName} : $from -> $to")

        Files.deleteIfExists(output)
        Files.createDirectories(output.parent)

        // tiny-remapper drops module-info.class, so keep the original bytes to re-add afterwards.
        val moduleInfo = ModuleInfoRemapper.read(input)

        val remapper = TinyRemapper.newRemapper()
            .withMappings(TinyUtils.createTinyMappingProvider(mappings.get().asFile.toPath(), from, to))
            .renameInvalidLocals(true)
            .rebuildSourceFilenames(true)
            .invalidLvNamePattern(Pattern.compile("\\$\\$\\d+"))
            .inferNameFromSameLvIndex(true)
            .build()

        try {
            OutputConsumerPath.Builder(output).build().use { consumer ->
                consumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper)
                remapper.readInputs(input)
                classpath.files
                    .filter { it.isFile }
                    .forEach { remapper.readClassPath(it.toPath()) }
                remapper.apply(consumer)
            }
        } finally {
            remapper.finish()
        }

        if (moduleInfo != null) {
            val mapped = ModuleInfoRemapper.remapInto(output, moduleInfo, mappings.get().asFile.toPath(), from, to)
            logger.lifecycle("Restored module-info.class ($mapped class renames applied)")
        }

        logger.lifecycle("Wrote ${output.fileName} (${Files.size(output) / 1024} KiB)")
    }
}
