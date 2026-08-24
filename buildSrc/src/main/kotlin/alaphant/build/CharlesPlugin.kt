package alaphant.build

import alaphant.build.tasks.mappings.AnalyzeMappingsTask
import alaphant.build.tasks.mappings.BootstrapNamesTask
import alaphant.build.tasks.dev.CharlesInfoTask
import alaphant.build.tasks.dev.CharlesRunTask
import alaphant.build.tasks.mappings.CheckLinkageTask
import alaphant.build.tasks.mappings.CheckMatcherTask
import alaphant.build.tasks.CheckReflectionSitesTask
import alaphant.build.tasks.dev.DecompileTask
import alaphant.build.tasks.mappings.EnigmaTask
import alaphant.build.tasks.dev.ExtractMetadataTask
import alaphant.build.tasks.mappings.GenerateIntermediaryTask
import alaphant.build.tasks.mappings.MatchVersionsTask
import alaphant.build.tasks.mappings.MergeNamedMappingsTask
import alaphant.build.tasks.mappings.PruneMappingsTask
import alaphant.build.tasks.mappings.RemapJarTask
import alaphant.build.tasks.mappings.ValidateMappingsTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Owns everything that touches the Charles install: locating, remapping, decompiling and indexing it.
 *
 * Resolved eagerly, and a missing install is a hard error -- the mod compiles against the remapped
 * jar, so deferring the failure only hides its cause.
 */
class CharlesPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("jvm-toolchains")

        val installDir = locateInstall(project)
        val plist = readPlist(project, installDir)
        val version = providers.gradleProperty("alaphant.charlesVersion").orNull
            ?: plist.version
            ?: throw GradleException(
                "Could not read the Charles version from ${installDir.parentFile}/Info.plist. " +
                    "Set alaphant.charlesVersion in gradle.properties."
            )

        val intermediaryJar = layout.buildDirectory.file(INTERMEDIARY_JAR).get().asFile
        val namedJar = layout.buildDirectory.file(NAMED_JAR).get().asFile
        val agentJar = layout.buildDirectory.file(AGENT_JAR).get().asFile

        // `add`, not `create`: resolved facts, with nothing for Gradle's decoration to do.
        val charles = CharlesExtension(installDir, version, namedJar, intermediaryJar, agentJar, plist)
        extensions.add(CharlesExtension::class.java, "charles", charles)
        logger.info("Charles $version at $installDir")

        val intermediaryFile = file("mappings/intermediary/$version.tiny")
        val ledgerFile = file("mappings/ledger.json")
        val namedDir = file("mappings/named")
        val readableNames = file("mappings/readable-names.txt")
        val packageNames = file("mappings/package-names.txt")
        val reflectionSites = file("mappings/reflection-sites.txt")
        val mergedMappings = layout.buildDirectory.file("mappings/charles-$version-v2.tiny")

        val decompilerClasspath = configurations.create("charlesDecompiler") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val enigmaClasspath = configurations.create("enigmaClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        tasks.register<GenerateIntermediaryTask>("generateIntermediary") {
            group = MAPPINGS_GROUP
            description = "Allocates the stable intermediary namespace for Charles $version."
            officialJar.set(charles.officialJar)
            this.readableNames.set(readableNames)
            this.packageNames.set(packageNames)
            charlesVersion.set(version)
            outputMappings.set(intermediaryFile)
            this.ledgerFile.set(ledgerFile)
            allowUnmatched.set(
                providers.gradleProperty("alaphant.allowUnmatchedIntermediary").map(String::toBoolean)
            )
        }

        tasks.register<MatchVersionsTask>("matchVersions") {
            group = MAPPINGS_GROUP
            description = "Carries intermediary IDs from a previous Charles release onto $version."
            officialJar.set(charles.officialJar)
            newVersion.set(version)
            previousJar.set(providers.gradleProperty("alaphant.previousCharlesInstall"))
            this.readableNames.set(readableNames)
            this.ledgerFile.set(ledgerFile)
            this.namedDir.set(namedDir)
            intermediaryDir.set(file("mappings/intermediary"))
            report.set(layout.buildDirectory.file("reports/mappings/match-$version.txt"))
        }

        val bootstrapNames = tasks.register<BootstrapNamesTask>("bootstrapNames") {
            group = MAPPINGS_GROUP
            description = "Recovers names the obfuscator failed to hide, writing them into mappings/named."
            officialJar.set(charles.officialJar)
            intermediaryMappings.set(intermediaryFile)
            legacyMappings.set(file("mappings/legacy/charles4.tiny"))
            this.namedDir.set(namedDir)
        }

        val mergeMappings = tasks.register<MergeNamedMappingsTask>("mergeMappings") {
            // The bootstrap writes into the named store rather than producing it, so order it by hand.
            mustRunAfter(bootstrapNames)
            group = MAPPINGS_GROUP
            description = "Merges the generated intermediary file and the Enigma named store into one tiny v2 file."
            intermediaryMappings.set(intermediaryFile)
            this.namedDir.set(namedDir)
            outputFile.set(mergedMappings)
        }

        val remapIntermediary = tasks.register<RemapJarTask>("remapIntermediary") {
            group = MAPPINGS_GROUP
            description = "Remaps charles.jar from obfuscated names to the stable intermediary namespace."
            inputJar.set(charles.officialJar)
            mappings.set(mergeMappings.flatMap { it.outputFile })
            fromNamespace.set("official")
            toNamespace.set("intermediary")
            classpath.from(charles.libraryJars)
            outputJar.set(intermediaryJar)
        }

        val remapNamed = tasks.register<RemapJarTask>("remapNamed") {
            group = MAPPINGS_GROUP
            description = "Remaps the intermediary jar to human-readable named form. The project compiles against this."
            inputJar.set(remapIntermediary.flatMap { it.outputJar })
            mappings.set(mergeMappings.flatMap { it.outputFile })
            fromNamespace.set("intermediary")
            toNamespace.set("named")
            classpath.from(charles.libraryJars)
            outputJar.set(namedJar)
        }

        tasks.register<DecompileTask>("decompile") {
            group = MAPPINGS_GROUP
            description = "Decompiles the named jar to Java sources for mapping work."
            inputJar.set(remapNamed.flatMap { it.outputJar })
            libraries.from(charles.libraryJars)
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
            description = "Reports mapping coverage per namespace, kind and package."
            mappings.set(mergeMappings.flatMap { it.outputFile })
        }

        val pruneMappings = tasks.register<PruneMappingsTask>("pruneMappings") {
            group = MAPPINGS_GROUP
            description = "Deletes named mappings with no intermediary element behind them."
            unconsumable.from(fileTree(namedDir) { include("java/**/*.mapping") })
            this.namedDir.set(namedDir)
        }

        tasks.register<ValidateMappingsTask>("validateMappings") {
            group = VERIFY_GROUP
            description = "Checks the named store for the mistakes that would otherwise pass silently."
            dependsOn(pruneMappings)
            intermediaryMappings.set(intermediaryFile)
            officialJar.set(charles.officialJar)
            this.namedDir.set(namedDir)
        }

        tasks.register<CheckMatcherTask>("checkMatcher") {
            group = VERIFY_GROUP
            description = "Scores the version matcher against a re-obfuscated copy of charles.jar."
            officialJar.set(charles.officialJar)
            this.readableNames.set(readableNames)
            this.ledgerFile.set(ledgerFile)
            charlesVersion.set(version)
            scrambledJar.set(layout.buildDirectory.file("charles/charles-scrambled.jar"))
            report.set(layout.buildDirectory.file("reports/mappings/matcher-self-check.txt"))
        }

        tasks.register<CheckLinkageTask>("checkLinkage") {
            group = VERIFY_GROUP
            description = "Resolves every reference in the remapped jar, so a mapping cannot break Charles silently."
            inputJar.set(remapNamed.flatMap { it.outputJar })
            libraries.from(charles.libraryJars)
        }

        tasks.register<CheckReflectionSitesTask>("checkReflectionSites") {
            group = VERIFY_GROUP
            description = "Fails if Charles loads a renamed class by name and no mixin patches the literal."
            officialJar.set(charles.officialJar)
            mappings.set(mergeMappings.flatMap { it.outputFile })
            handled.set(reflectionSites)
        }

        val toolchains = extensions.getByType(JavaToolchainService::class.java)

        val charlesLauncher = objects.property(JavaLauncher::class.java)
        pluginManager.withPlugin("java-base") {
            val toolchain = extensions.getByType(JavaPluginExtension::class.java).toolchain
            charlesLauncher.set(toolchains.launcherFor(toolchain))
        }

        tasks.register<EnigmaTask>("enigma") {
            group = MAPPINGS_GROUP
            description = "Opens the named mappings in the Enigma GUI, editing intermediary -> named."
            this.enigmaClasspath.from(enigmaClasspath)
            inputJar.set(remapIntermediary.flatMap { it.outputJar })
            libraries.from(charles.libraryJars)
            mappingsDir.set(namedDir)
            javaLauncher.set(charlesLauncher)
        }

        val assembleModulePath = tasks.register<Sync>("assembleModulePath") {
            group = RUN_GROUP
            description = "Assembles Charles' module path with the remapped charles.jar substituted in."
            from(charles.libraryJars)
            from(remapNamed.flatMap { it.outputJar }) { rename { CHARLES_JAR } }
            into(layout.buildDirectory.dir("charles/modules"))
        }

        val devProfileDir = layout.buildDirectory.dir(DEV_PROFILE).get().asFile

        val userJvmArgs = providers.gradleProperty("alaphant.jvmArgs")
            .map { it.split(' ', '\t', '\n').filter(String::isNotBlank) }
            .orElse(emptyList())

        val devProfileArgs = listOf(
            "-Dcharles.config=${File(devProfileDir, CHARLES_CONFIG)}",
            "-Dcharles.proxyPort=$DEV_PROXY_PORT",
            "-Dcharles.socksProxyPort=$DEV_SOCKS_PORT",
        )

        // `defaults` land after the dev profile and before `alaphant.jvmArgs`, so the last `-D` on
        // the command line is always the user's -- any of these can be turned back off from Gradle.
        fun registerRun(
            name: String,
            describedAs: String,
            defaults: List<String> = emptyList(),
            configure: CharlesRunTask.() -> Unit = {},
        ) =
            tasks.register<CharlesRunTask>(name) {
                group = RUN_GROUP
                description = describedAs
                dependsOn(assembleModulePath)
                modulePath.set(layout.buildDirectory.dir("charles/modules"))
                workingDir.set(layout.projectDirectory)
                jvmOptions.set(plist.jvmOptions)
                mainModuleAndClass.set(plist.mainModuleAndClass ?: DEFAULT_MAIN)
                nativeLibraryPath.set(charles.nativeLibraryDir.absolutePath)
                javaLauncher.set(charlesLauncher)
                extraJvmArgs.set(userJvmArgs.map { extra -> devProfileArgs + defaults + extra })
                // Charles writes the config file, but not the directory holding it.
                doFirst { devProfileDir.mkdirs() }
                configure()
            }

        registerRun("run", "Runs Charles from the remapped module path, unpatched.")

        registerRun(
            "runWithMod",
            "Runs Charles from the remapped module path with the Alaphant agent attached.",
            defaults = listOf("-Dmixin.debug.export=true"),
        ) {
            // By name: `agentJar` is the mod's own packaging and belongs to the root build script,
            // which writes it to the fixed path this task reads -- the same shape as `remapNamed`
            // and `charles.namedJar`.
            dependsOn("agentJar")
            this.agentJar.set(charles.agentJar)
        }

        tasks.register<CharlesInfoTask>("charlesInfo") {
            group = CHARLES_GROUP
            description = "Prints what the build resolved, so a broken setup is obvious."
            installDirPath.set(installDir.absolutePath)
            charlesVersion.set(version)
            libraryCount.set(charles.libraryJars.size)
            intermediaryFilePath.set(intermediaryFile.path)
            namedDirPath.set(namedDir.path)
            namedJarPath.set(namedJar.path)
        }
    }

    private fun locateInstall(project: Project): File {
        val configured = project.providers.gradleProperty("alaphant.charlesInstall").orNull
        val candidates = when {
            configured != null -> listOf(File(configured))
            else -> listOf(project.file("target"), File(MACOS_INSTALL))
        }

        candidates.firstOrNull { File(it, CHARLES_JAR).isFile }?.let { return it }

        throw GradleException(
            buildString {
                appendLine("No Charles install found. Alaphant patches Charles, so there is nothing to build without it.")
                appendLine()
                appendLine("Looked for:")
                candidates.forEach { appendLine("  ${File(it, CHARLES_JAR).absolutePath}") }
                appendLine()
                appendLine("Install Charles 5, or copy its charles.jar into target/, or set")
                appendLine("  alaphant.charlesInstall=/path/to/Charles.app/Contents/Java")
                append("in gradle.properties. Charles is never redistributed -- use your own licensed install.")
            }
        )
    }

    /** Read through `fileContents` so the configuration cache invalidates when the plist changes. */
    private fun readPlist(project: Project, installDir: File): InfoPlist.Config {
        val contents = installDir.parentFile ?: return InfoPlist.Config(emptyList(), null, null)
        val plist = File(contents, "Info.plist")
        val text = project.providers.fileContents(project.layout.file(project.provider { plist })).asText.orNull
            ?: return InfoPlist.Config(emptyList(), null, null)
        return InfoPlist.parse(text, contents.parentFile)
    }

    private companion object {
        const val CHARLES_JAR = "charles.jar"
        const val MACOS_INSTALL = "/Applications/Charles.app/Contents/Java"
        const val MAPPINGS_GROUP = "mappings"
        const val CHARLES_GROUP = "charles"
        const val VERIFY_GROUP = "verification"
        const val RUN_GROUP = "run"
        const val DEFAULT_MAIN = "com.charlesproxy/com.charlesproxy.main.MainWithClassLoader"

        const val DEV_PROFILE = "charles/dev"
        const val CHARLES_CONFIG = "charles.config"
        const val DEV_PROXY_PORT = 8899
        const val DEV_SOCKS_PORT = 8900

        /** Stable paths: the root build script and IntelliJ both point straight at these files. */
        const val INTERMEDIARY_JAR = "charles/charles-intermediary.jar"
        const val NAMED_JAR = "charles/charles-named.jar"
        const val AGENT_JAR = "agent/alaphant-agent.jar"
    }
}
