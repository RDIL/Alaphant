package alaphant.build

import java.io.File

/**
 * Decides whether an official Charles name came out of the obfuscator.
 *
 * Charles 5's obfuscator leaves packages and 369 outer classes alone and rewrites everything else to
 * four-letter names drawn from a small pool, plus a short tail (`a_` … `j_`, `b`). Four letters is
 * also a fine length for a real identifier, and case shape cannot separate the two — `fluh`, `sesd`
 * and `unek` are obfuscator output while `data`, `host` and `tree` are not. So short names default
 * to obfuscated and [readableNames] is the exception list.
 *
 * That default is the safe direction. Treating a readable name as obfuscated costs one needless
 * placeholder, and the identity bootstrap hands the real name back in the `named` namespace.
 * Treating an obfuscated name as readable leaks a name that churns on every Charles release into
 * `intermediary`, which is the one thing the three-namespace scheme exists to prevent.
 */
class NameClassifier(
    private val readableClassNames: Set<String>,
    private val readableMemberNames: Set<String>,
) {
    /**
     * Package names are lower case by universal convention, so an upper-case letter in a four-letter
     * segment is decisive. In 5.0.3 this identifies exactly `Jpqc`, `MkAr`, `rkIB`, `tpaR`, `vGmK`
     * and `xvJk`, and leaves `diff`, `find`, `http`, `json`, `main`, `tree` and `util` alone.
     */
    fun isObfuscatedPackageSegment(segment: String): Boolean =
        segment.length == 4 && segment.all { it.isLetter() } && segment.any { it.isUpperCase() }

    /**
     * Any four-letter class name, unless listed. Measured against 5.0.3: this flags 1589 outer
     * classes covering all 72 pool names, with no false positive — every class Charles kept the name
     * of is five characters or more. The only four-letter names belonging to real classes are nested
     * (`…$Mode`, `…$Text`, `…$Type`), which is what the list holds.
     */
    fun isObfuscatedClassName(simpleName: String): Boolean =
        simpleName.length == 4 && simpleName.all { it.isLetter() } && simpleName !in readableClassNames

    /**
     * Four letters or fewer and letters-only (a trailing `_` allowed, which is the shape of the
     * short tail), unless listed. Note that an all-caps four-letter name is *not* an exception:
     * `QVOO` and `LPMX` are obfuscator output, and 5.0.3 has no real four-letter constant.
     */
    fun isObfuscatedMemberName(name: String): Boolean {
        if (name in readableMemberNames) return false
        if (name.length > 4 || name.isEmpty()) return false
        return name.all { it.isLetter() } || (name.dropLast(1).all { it.isLetter() } && name.last() == '_')
    }

    companion object {
        /**
         * Reads `mappings/readable-names.txt`: `#` comments, `[classes]` / `[members]` sections, one
         * name per line. A missing file means no exceptions, which is safe but noisy.
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
