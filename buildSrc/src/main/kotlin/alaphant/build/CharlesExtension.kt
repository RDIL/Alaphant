package alaphant.build

import java.io.File

/**
 * The Charles install this build maps and patches, resolved once when the plugin applies.
 *
 * Everything here is a plain value rather than a lazy `Property`. A Charles install is either there
 * or it is not, and if it is not there is no build to configure — so there is nothing to defer, and
 * plain values keep the build script and `:mod` readable.
 */
class CharlesExtension(
    /** `Charles.app/Contents/Java`, or wherever `charles.jar` was found. */
    val installDir: File,
    /** The version, from the app bundle's `Info.plist`. */
    val version: String,
    /**
     * The remapped jar the project compiles against, at a fixed path.
     *
     * Fixed on purpose: it is declared as a plain file dependency so IntelliJ registers it as a
     * library and re-reads it the moment `remapNamed` rewrites it. Route it through a configuration
     * or a project dependency instead and the IDE stops showing renames until you reload Gradle.
     */
    val namedJar: File,
    /** The intermediary-namespace jar, which is what Enigma edits against. */
    val intermediaryJar: File,
    /** JVM options and main class as Charles' own launcher declares them. */
    internal val plist: InfoPlist.Config,
) {
    val officialJar: File = File(installDir, "charles.jar")

    /** Everything on Charles' module path except Charles itself. */
    val libraryJars: List<File> = installDir.listFiles()
        ?.filter { it.isFile && it.extension == "jar" && it.name != officialJar.name }
        ?.sortedBy { it.name }
        ?: emptyList()

    /** Charles' native libraries (`Contents/MacOS` on macOS). */
    val nativeLibraryDir: File = File(installDir.parentFile, "MacOS")
}
