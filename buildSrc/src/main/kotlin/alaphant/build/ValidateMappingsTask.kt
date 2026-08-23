package alaphant.build

import net.fabricmc.mappingio.format.enigma.EnigmaDirReader
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The correctness gate on the named store. Every check here catches something that is otherwise
 * invisible: the file still parses, the build still succeeds, and the damage shows up later as a
 * confusing jar or a silently dropped mapping.
 *
 * What it does *not* check is whether a name is any good. Nothing can, which is why the naming
 * conventions lean on `maybe` prefixes and evidence comments instead.
 */
abstract class ValidateMappingsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val intermediaryMappings: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val namedDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun validate() {
        val intermediary = IntermediaryIndex.read(intermediaryMappings.get().asFile)
        val problems = mutableListOf<String>()

        val dir = namedDir.get().asFile
        val files = dir.walkTopDown().count { it.isFile && it.extension == "mapping" }
        if (files == 0) {
            logger.lifecycle("Named store is empty; nothing to validate")
            return
        }

        // 1. The store parses at all.
        val named = MemoryMappingTree()
        try {
            EnigmaDirReader.read(dir.toPath(), "intermediary", "named", named)
        } catch (e: Exception) {
            throw GradleException("mappings/named does not parse as an Enigma directory: ${e.message}", e)
        }

        val knownClasses = intermediary.classMap.values.toSet()
        val takenNames = HashMap<String, String>()

        for (cls in named.classes) {
            val where = cls.srcName

            // 2. The intermediary element it names has to exist. Catches invented IDs, which is the
            //    likeliest way for generated or hand-written mappings to be quietly wrong.
            if (cls.srcName !in knownClasses) {
                problems += "$where: no such class in the intermediary namespace"
                continue
            }

            val name = cls.getDstName(0)
            if (name != null) {
                checkIdentifier(name, "$where -> $name", problems)

                // 3. Two classes with one name collide in the remapped jar and share one Enigma file,
                //    where the second silently overwrites the first.
                takenNames.put(name, where)?.let { first ->
                    problems += "$where and $first are both named $name"
                }

                // 4. Enigma reads `$` as nesting, so a flat class given a nested name comes back
                //    truncated on the next save. Real nested classes are fine.
                if ('$' in name.substringAfterLast('/') && '$' !in cls.srcName) {
                    problems += "$where -> $name: a flat class cannot take a nested name, Enigma will truncate it"
                }

                // 5. A provisional name has to carry its reasoning, or it is just a guess with a
                //    prefix on it.
                checkProvisional(name, cls.comment, where, problems)
            }

            val members = HashMap<String, String>()
            for (field in cls.fields) {
                val key = "${field.srcName}${field.srcDesc}"
                validateMember(
                    intermediary = intermediary.field(cls.srcName, field.srcName, field.srcDesc ?: "") != null ||
                        fieldExists(intermediary, cls, field),
                    where = "$where.${field.srcName}",
                    name = field.getDstName(0),
                    comment = field.comment,
                    dedupeKey = { "${it}${field.srcDesc}" },
                    members = members,
                    problems = problems,
                )
                members.putIfAbsent(key, key)
            }
            for (method in cls.methods) {
                validateMember(
                    intermediary = true,
                    where = "$where.${method.srcName}${method.srcDesc}",
                    name = method.getDstName(0),
                    comment = method.comment,
                    dedupeKey = { "${it}${method.srcDesc}" },
                    members = members,
                    problems = problems,
                )
            }
        }

        logger.lifecycle("Validated $files Enigma file(s), ${named.classes.size} classes")
        if (problems.isEmpty()) {
            logger.lifecycle("No problems found")
            return
        }

        throw GradleException(
            "${problems.size} problem(s) in mappings/named:\n" + problems.joinToString("\n") { "  $it" }
        )
    }

    private fun fieldExists(index: IntermediaryIndex, cls: MappingTree.ClassMapping, field: MappingTree.FieldMapping): Boolean {
        // Members of a class the intermediary file does not carry are readable-name members, which
        // are legitimate to comment on or rename; there is nothing to check them against.
        return index.classMap.values.contains(cls.srcName) && field.srcDesc != null
    }

    private inline fun validateMember(
        intermediary: Boolean,
        where: String,
        name: String?,
        comment: String?,
        dedupeKey: (String) -> String,
        members: MutableMap<String, String>,
        problems: MutableList<String>,
    ) {
        if (!intermediary) {
            problems += "$where: no such member in the intermediary namespace"
            return
        }
        if (name == null) return

        checkIdentifier(name, "$where -> $name", problems)
        checkProvisional(name, comment, where, problems)

        // 6. Two members of one class with the same name and descriptor is not valid bytecode.
        members.put(dedupeKey(name), where)?.let { first ->
            problems += "$where and $first both map to $name with the same descriptor"
        }
    }

    private fun checkIdentifier(name: String, where: String, problems: MutableList<String>) {
        for (segment in name.split('/')) {
            for (part in segment.split('$')) {
                if (part.isEmpty()) continue
                if (!IDENTIFIER.matches(part)) {
                    problems += "$where: \"$part\" is not a valid Java identifier"
                } else if (part in JAVA_KEYWORDS) {
                    problems += "$where: \"$part\" is a Java keyword"
                }
            }
        }
    }

    private fun checkProvisional(name: String, comment: String?, where: String, problems: MutableList<String>) {
        val simple = name.substringAfterLast('/').substringAfterLast('$')
        if (!PROVISIONAL.matches(simple)) return

        if (comment.isNullOrBlank()) {
            problems += "$where: a provisional \"$simple\" needs a COMMENT saying what the evidence was"
        }
        // Guards against a provisional built on an obfuscated stem -- `Maybei`, `maybeA`. A name that
        // says "probably one letter" is worse than no name.
        if (simple.removePrefix("maybe").removePrefix("Maybe").length < 3) {
            problems += "$where: \"$simple\" has nothing after the prefix worth keeping"
        }
    }

    private companion object {
        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        val PROVISIONAL = Regex("""[Mm]aybe[A-Z_].*""")

        val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_",
        )
    }
}
