package alaphant.build

import java.io.File

/**
 * The Charles install this build maps and patches, resolved once when the plugin applies.
 *
 * Plain values rather than lazy `Property`s: the install is either there or there is no build to
 * configure, so there is nothing to defer.
 */
class CharlesExtension(
    /** `Charles.app/Contents/Java`, or wherever `charles.jar` was found. */
    val installDir: File,
    /** The version, from the app bundle's `Info.plist`. */
    val version: String,
    /**
     * The remapped jar the project compiles against. The path is fixed so IntelliJ can register it as
     * a plain library and pick up renames without a Gradle reload.
     */
    val namedJar: File,
    /** The intermediary-namespace jar, which is what Enigma edits against. */
    val intermediaryJar: File,
    /**
     * The self-contained `-javaagent` jar that patches Charles. Fixed path for the same reason
     * [namedJar] has one: `runWithMod` and anything launching Charles by hand both point straight
     * at it.
     */
    val agentJar: File,
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
