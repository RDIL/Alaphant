package alaphant.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Owns everything that touches the Charles install: locating it, remapping it, decompiling it, and indexing it.
 */
class CharlesPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("jvm-toolchains")

        val charles = extensions.create<CharlesExtension>("charles")

        // capture as plain file to hack around config cache not liking anything else
        val projectDir = layout.projectDirectory.asFile

        charles.installDir.convention(
            layout.dir(
                providers.gradleProperty("alaphant.charlesInstall").map { File(it) }.orElse(
                    providers.provider {
                        DEFAULT_INSTALL_DIRS
                            .map { if (File(it).isAbsolute) File(it) else File(projectDir, it) }
                            .firstOrNull { File(it, CHARLES_JAR).isFile }
                    }
                )
            )
        )

        charles.version.convention(
            providers.gradleProperty("alaphant.charlesVersion").orElse(
                charles.installDir.map { dir -> detectVersion(dir.asFile) }.orElse("unknown")
            )
        )

        charles.mappingFile.convention(
            layout.file(charles.version.map { File(projectDir, "mappings/charles-$it.tiny") })
        )

        val officialJar: Provider<File> = charles.installDir.map { it.file(CHARLES_JAR).asFile }

        /** Charles' dependency jars (everything on its module path except Charles itself). */
        val charlesLibs: FileCollection = files(charles.installDir.map { dependencyJars(it.asFile) })

        val decompilerClasspath = configurations.create("charlesDecompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val remapIntermediary = tasks.register<RemapJarTask>("remapIntermediary") {
            group = MAPPINGS_GROUP
            description = "Remaps charles.jar from obfuscated names to the stable intermediary namespace."
            inputJar.fileProvider(officialJar)
            mappings.set(charles.mappingFile)
            fromNamespace.set("official")
            toNamespace.set("intermediary")
            classpath.from(charlesLibs)
            outputJar.set(layout.buildDirectory.file("charles/charles-intermediary.jar"))
        }

        val remapNamed = tasks.register<RemapJarTask>("remapNamed") {
            group = MAPPINGS_GROUP
            description = "Remaps the intermediary jar to human-readable named form. `:mod` compiles against this."
            inputJar.set(remapIntermediary.flatMap { it.outputJar })
            mappings.set(charles.mappingFile)
            fromNamespace.set("intermediary")
            toNamespace.set("named")
            classpath.from(charlesLibs)
            outputJar.set(layout.buildDirectory.file("charles/charles-named.jar"))
        }

        tasks.register<DecompileTask>("decompile") {
            group = MAPPINGS_GROUP
            description = "Decompiles the named jar to Java sources for mapping work."
            inputJar.set(remapNamed.flatMap { it.outputJar })
            libraries.from(charlesLibs)
            this.decompilerClasspath.from(decompilerClasspath)
            outputDir.set(layout.buildDirectory.dir("decompiled"))
        }

        tasks.register<ExtractMetadataTask>("extractMetadata") {
            group = MAPPINGS_GROUP
            description = "Indexes class hierarchy, string constants and member xrefs for mapping work packets."
            inputJar.set(remapNamed.flatMap { it.outputJar })
            outputDir.set(layout.buildDirectory.dir("metadata"))
        }

        tasks.register<AnalyzeMappingsTask>("analyzeMappings") {
            group = MAPPINGS_GROUP
            description = "Reports mapping coverage per namespace and kind."
            mappings.set(charles.mappingFile)
        }

        val plistConfig = charles.installDir.map { dir ->
            val contents = dir.asFile.parentFile
            InfoPlist.read(File(contents, "Info.plist"), contents.parentFile)
        }

        val assembleModulePath = tasks.register<Sync>("assembleModulePath") {
            group = RUN_GROUP
            description = "Assembles Charles' module path with the remapped charles.jar substituted in."
            from(charlesLibs)
            from(remapNamed.flatMap { it.outputJar }) { rename { CHARLES_JAR } }
            into(layout.buildDirectory.dir("charles/modules"))
        }

        val toolchains = extensions.getByType(JavaToolchainService::class.java)

        tasks.register<CharlesRunTask>("run") {
            group = RUN_GROUP
            description = "Runs Charles from the remapped module path."
            dependsOn(assembleModulePath)
            modulePath.set(layout.buildDirectory.dir("charles/modules"))
            jvmOptions.set(plistConfig.map { it.jvmOptions })
            mainModuleAndClass.set(plistConfig.map { it.mainModuleAndClass ?: DEFAULT_MAIN })
            nativeLibraryPath.set(charles.installDir.map { File(it.asFile.parentFile, "MacOS").absolutePath })
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        }

        tasks.register<CharlesInfoTask>("charlesInfo") {
            group = MAPPINGS_GROUP
            description = "Prints the detected Charles install, so a broken setup is obvious."
            installDir.set(charles.installDir.map { it.asFile.absolutePath })
            charlesVersion.set(charles.version)
            libraryPaths.set(charles.installDir.map { dir -> dependencyJars(dir.asFile).map { it.absolutePath } }.orElse(emptyList()))
            mappingFile.set(charles.mappingFile.map { it.asFile.path })
        }

        // Published so `:mod` can compile against the named jar without a cross-project file reference.
        configurations.create("namedJar") {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
        artifacts.add("namedJar", remapNamed.flatMap { it.outputJar })
    }

    private companion object {
        /** Everything on Charles' module path except Charles itself. */
        fun dependencyJars(installDir: File): List<File> =
            installDir.listFiles()
                ?.filter { it.isFile && it.extension == "jar" && it.name != CHARLES_JAR }
                ?.sortedBy { it.name }
                ?: emptyList()

        fun detectVersion(installDir: File): String {
            // installDir is Charles.app/Contents/Java, so Info.plist sits one level up.
            val plist = installDir.parentFile?.resolve("Info.plist")?.takeIf { it.isFile } ?: return "unknown"
            return PLIST_VERSION.find(plist.readText())?.groupValues?.get(1) ?: "unknown"
        }

        const val CHARLES_JAR = "charles.jar"
        const val MAPPINGS_GROUP = "charles"
        const val RUN_GROUP = "run"
        const val DEFAULT_MAIN = "com.charlesproxy/com.charlesproxy.main.MainWithClassLoader"

        val DEFAULT_INSTALL_DIRS = listOf(
            "target",
            "/Applications/Charles.app/Contents/Java",
        )

        val PLIST_VERSION =
            Regex("""<key>CFBundleShortVersionString</key>\s*<string>([^<]+)</string>""")
    }
}
