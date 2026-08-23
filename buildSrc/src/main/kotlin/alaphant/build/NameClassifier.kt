package alaphant.build

import java.io.File

/**
 * Decides whether an official Charles name came out of the obfuscator.
 *
 * The obfuscator leaves packages and 369 outer classes alone and rewrites the rest to four-letter
 * names, which is also a fine length for a real identifier — `fluh` is output, `host` is not. So
 * short names default to obfuscated, with `mappings/readable-names.txt` as the exception list.
 *
 * That direction is the safe one: a readable name treated as obfuscated costs one needless
 * placeholder, while the reverse leaks a name that churns every release into `intermediary`.
 */
class NameClassifier(
    private val readableClassNames: Set<String>,
    private val readableMemberNames: Set<String>,
) {
    /**
     * An upper-case letter in a four-letter segment, which is decisive because package names are
     * lower case by convention: in 5.0.3 it flags `MkAr` and friends and leaves `http`, `util` alone.
     */
    fun isObfuscatedPackageSegment(segment: String): Boolean =
        segment.length == 4 && segment.all { it.isLetter() } && segment.any { it.isUpperCase() }

    /**
     * Any four-letter class name, unless listed. In 5.0.3 every class Charles kept the name of is
     * five characters or more; the only real four-letter names are nested (`…$Mode`, `…$Type`).
     */
    fun isObfuscatedClassName(simpleName: String): Boolean =
        simpleName.length == 4 && simpleName.all { it.isLetter() } && simpleName !in readableClassNames

    /**
     * Four letters or fewer, letters-only with an optional trailing `_`, unless listed. All-caps is
     * no exception: `QVOO` is obfuscator output and 5.0.3 has no real four-letter constant.
     */
    fun isObfuscatedMemberName(name: String): Boolean {
        if (name in readableMemberNames) return false
        if (name.length > 4 || name.isEmpty()) return false
        return name.all { it.isLetter() } || (name.dropLast(1).all { it.isLetter() } && name.last() == '_')
    }

    companion object {
        /**
         * Reads `mappings/readable-names.txt`: `#` comments, `[classes]` / `[members]` sections, one
         * name per line. A missing file means no exceptions.
         */
        fun read(file: File): NameClassifier {
            if (!file.isFile) return NameClassifier(emptySet(), emptySet())

            val sections = mutableMapOf("classes" to mutableSetOf<String>(), "members" to mutableSetOf())
            var current = "members"

            for (raw in file.readLines()) {
                val line = raw.substringBefore('#').trim()
                when {
                    line.isEmpty() -> continue
                    line.startsWith("[") && line.endsWith("]") -> {
                        current = line.removeSurrounding("[", "]").trim()
                        require(current in sections) { "${file.name}: unknown section [$current]" }
                    }
                    else -> sections.getValue(current).add(line)
                }
            }

            return NameClassifier(sections.getValue("classes"), sections.getValue("members"))
        }
    }
}
