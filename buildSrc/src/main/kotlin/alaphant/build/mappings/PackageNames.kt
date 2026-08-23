package alaphant.build.mappings

import java.io.File

/**
 * Obfuscated package segments whose real name has been recovered, from `mappings/package-names.txt`.
 *
 * The exception list for packages, in the same spirit as `readable-names.txt` is for names: the
 * classifier cannot tell `MkAr` from a real segment, so the answer is supplied. Keyed on the whole
 * official path rather than the segment alone, because `MkAr` means something different in each
 * place it appears -- `pcap` under `export`, `server` under `xk72/proxy`, `mozilla` at the root.
 */
object PackageNames {
    /** Official package path -> the real name of its last segment. */
    fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()

        return file.readLines().mapIndexedNotNull { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapIndexedNotNull null

            val (path, name) = line.split('=', limit = 2).map(String::trim).also {
                require(it.size == 2) { "${file.name}:${index + 1}: expected \"<package path> = <name>\"" }
            }
            require('/' !in name) { "${file.name}:${index + 1}: \"$name\" is a segment, not a path" }
            path to name
        }.toMap()
    }
}
