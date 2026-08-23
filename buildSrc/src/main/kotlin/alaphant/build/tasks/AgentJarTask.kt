package alaphant.build.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * Bundles the mod and everything it needs at runtime into one self-contained `-javaagent` jar.
 *
 * It has to be self-contained: `-javaagent` appends exactly one jar to the system class path, and
 * Charles' own dependencies are on the module path where the agent cannot see them. Mixin also
 * publishes no POM dependencies at all, so ASM, Guava and Gson are declared by the root build and
 * arrive here through the runtime classpath.
 *
 * Written by hand rather than with `Jar { from(zipTree(...)) }` for one reason that matters: the
 * mod's own entries are written first and duplicates are dropped, so `META-INF/services` resolves
 * to Alaphant's Mixin service rather than Mixin's own LaunchWrapper and ModLauncher declarations,
 * which would otherwise be found first and fail to initialise.
 */
abstract class AgentJarTask : DefaultTask() {
    /** The mod's compiled classes and resources. Written first, so the mod always wins a clash. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modClasses: ConfigurableFileCollection

    /** Runtime dependencies, unpacked into the jar in classpath order. */
    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val premainClass: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun bundle() {
        val target = outputJar.get().asFile
        target.parentFile.mkdirs()

        val jars = libraries.filter(File::isFile).sortedBy(File::getName)
        val written = HashSet<String>()
        var dropped = 0

        JarOutputStream(target.outputStream().buffered(), manifest(jars)).use { jar ->
            modClasses.filter(File::isDirectory).forEach { root ->
                root.walkTopDown().filter(File::isFile).forEach { file ->
                    val name = file.relativeTo(root).invariantSeparatorsPath
                    if (accept(name) && written.add(name)) jar.write(name, file.readBytes())
                }
            }

            jars.forEach { library ->
                JarFile(library).use { archive ->
                    archive.entries().asSequence().filterNot(ZipEntry::isDirectory).forEach { entry ->
                        if (!accept(entry.name)) return@forEach
                        if (written.add(entry.name)) {
                            jar.write(entry.name, archive.getInputStream(entry).use { it.readBytes() })
                        } else {
                            dropped++
                        }
                    }
                }
            }
        }

        logger.lifecycle("Agent jar: ${written.size} entries from ${jars.size + 1} sources ($dropped duplicates dropped)")
    }

    private fun manifest(jars: List<File>) = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name("Premain-Class")] = premainClass.get()
        // Mixin only transforms classes as they load, but retransform capability costs nothing and
        // makes the agent usable with `-XX:+EnableDynamicAgentLoading` style attach experiments.
        mainAttributes[Attributes.Name("Can-Retransform-Classes")] = "true"
        mainAttributes[Attributes.Name("Can-Redefine-Classes")] = "true"
        jars.forEach { library -> carryVersion(library, entries) }
    }

    /**
     * Restates each library's version as a per-package manifest section.
     *
     * `Package.getImplementationVersion()` reads the manifest of the jar a package came from, and a
     * merged jar has only one manifest -- so libraries that inspect their own version see nothing.
     * That is not cosmetic: Mixin reads ASM's version this way to decide which compatibility levels
     * it can offer, and reports ASM 9.0 without it, which rules out `JAVA_17`.
     */
    private fun carryVersion(library: File, sections: MutableMap<String, Attributes>) {
        JarFile(library).use { archive ->
            val main = archive.manifest?.mainAttributes ?: return
            val version = main.getValue(Attributes.Name.IMPLEMENTATION_VERSION) ?: return
            val title = main.getValue(Attributes.Name.IMPLEMENTATION_TITLE)

            archive.entries().asSequence()
                .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .map { it.name.substringBeforeLast('/', missingDelimiterValue = "") }
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { directory ->
                    // A section applies to exactly one package, so subpackages each need their own.
                    sections.computeIfAbsent("$directory/") {
                        Attributes().apply {
                            putValue("Implementation-Version", version)
                            title?.let { putValue("Implementation-Title", it) }
                        }
                    }
                }
        }
    }

    /**
     * Signature files are void once the archive is rebuilt, and a stray `module-info` would turn the
     * agent jar into a module -- which it must not be, since it lives on the class path and its
     * packages have to stay in the unnamed module for Charles to read them.
     */
    private fun accept(name: String): Boolean = when {
        name == JarFile.MANIFEST_NAME || name == "META-INF/INDEX.LIST" -> false
        name.endsWith("module-info.class") -> false
        name.startsWith("META-INF/") && SIGNATURES.any { name.endsWith(it) } -> false
        else -> true
    }

    private fun JarOutputStream.write(name: String, bytes: ByteArray) {
        // A fixed timestamp keeps the jar byte-identical between builds of the same inputs.
        putNextEntry(JarEntry(name).apply { time = FIXED_TIME })
        write(bytes)
        closeEntry()
    }

    private companion object {
        val SIGNATURES = listOf(".SF", ".DSA", ".RSA", ".EC")

        /** 1980-02-01, the same constant Gradle uses for reproducible archives. */
        const val FIXED_TIME = 318384000000L
    }
}
