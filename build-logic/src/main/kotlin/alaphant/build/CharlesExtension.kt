package alaphant.build

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the Charles install this build maps and patches.
 *
 * Nothing here is required on a machine that only needs to read the repo: every task that actually touches Charles validates its own inputs, so `./gradlew tasks` works on a checkout with no Charles present.)
 */
abstract class CharlesExtension {
    /** Directory holding `charles.jar` and its dependency jars (`Charles.app/Contents/Java`). */
    abstract val installDir: DirectoryProperty

    /** Charles version, used to name generated mapping files. Detected from the app bundle. */
    abstract val version: Property<String>

    /** The merged tiny v2 mapping file: `official` -> `intermediary` -> `named`. */
    abstract val mappingFile: org.gradle.api.file.RegularFileProperty
}
