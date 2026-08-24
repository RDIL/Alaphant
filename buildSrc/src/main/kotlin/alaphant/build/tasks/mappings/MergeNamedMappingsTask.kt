package alaphant.build.tasks.mappings

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingWriter
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.format.enigma.EnigmaDirReader
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Collapses the two halves of the mapping store into the single three-namespace tiny v2 file the
 * remap tasks consume.
 *
 * `official -> intermediary` comes from the generated tiny file, `intermediary -> named` from the
 * Enigma directory, joined on the intermediary name — so a Charles upgrade reissues the left column
 * and leaves the right one alone.
 *
 * Descriptors need translating too, not just names: the Enigma store was authored against the
 * intermediary jar, so it names intermediary types where the generated file names official ones.
 */
@CacheableTask
abstract class MergeNamedMappingsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val intermediaryMappings: RegularFileProperty

    /** Enigma directory format, one `.mapping` file per top-level class. May not exist yet. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val namedDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun merge() {
        val intermediary = MemoryMappingTree().also {
            MappingReader.read(intermediaryMappings.get().asFile.toPath(), MappingFormat.TINY_2_FILE, it)
        }

        val named = MemoryMappingTree()
        val namedRoot = namedDir.orNull?.asFile
        val namedFiles = namedRoot?.walkTopDown()?.count { it.isFile && it.extension == "mapping" } ?: 0
        if (namedRoot != null && namedFiles > 0) {
            EnigmaDirReader.read(namedRoot.toPath(), INTERMEDIARY, NAMED, named)
        }

        val toIntermediary = Descriptors(
            intermediary.classes.associate { it.srcName to (it.getDstName(0) ?: it.srcName) }
        )
        val toOfficial = Descriptors(
            intermediary.classes.associate { (it.getDstName(0) ?: it.srcName) to it.srcName }
        )

        val file = outputFile.get().asFile.also { it.parentFile?.mkdirs() }
        val tinyWriter: MappingWriter = checkNotNull(MappingWriter.create(file.toPath(), MappingFormat.TINY_2_FILE)) {
            "mapping-io has no tiny v2 writer"
        }
        val emitter = Emitter(tinyWriter, toOfficial)
        tinyWriter.use { writer ->
            writer.visitHeader()
            writer.visitNamespaces(OFFICIAL, listOf(INTERMEDIARY, NAMED))
            writer.visitContent()

            val emitted = HashSet<String>()

            for (cls in intermediary.classes.sortedBy { it.srcName }) {
                val official = cls.srcName
                val intermediaryName = cls.getDstName(0) ?: official
                emitted.add(intermediaryName)

                val namedCls = named.getClass(intermediaryName)
                if (!emitter.visitClass(official, intermediaryName, namedCls)) continue

                val seenFields = HashSet<String>()
                for (field in cls.fields.sortedWith(BY_SRC)) {
                    val member = field.getDstName(0) ?: field.srcName
                    val desc = toIntermediary.map(field.srcDesc)
                    seenFields.add("$member$desc")
                    emitter.visitMember(
                        MappedElementKind.FIELD, field.srcName, field.srcDesc, member,
                        namedCls?.getField(member, desc),
                    )
                }

                val seenMethods = HashSet<String>()
                for (method in cls.methods.sortedWith(BY_SRC)) {
                    val member = method.getDstName(0) ?: method.srcName
                    val desc = toIntermediary.map(method.srcDesc)
                    seenMethods.add("$member$desc")
                    emitter.visitMember(
                        MappedElementKind.METHOD, method.srcName, method.srcDesc, member,
                        namedCls?.getMethod(member, desc),
                    )
                }

                namedCls?.let { emitter.visitUnjoinedMembers(it, seenFields, seenMethods) }
            }

            // Likewise for classes whose official name was already readable.
            for (cls in named.classes.sortedBy { it.srcName }) {
                if (cls.srcName in emitted) continue
                if (!emitter.visitClass(cls.srcName, cls.srcName, cls)) continue
                emitter.visitUnjoinedMembers(cls, emptySet(), emptySet())
            }

            writer.visitEnd()
        }

        logger.lifecycle(
            "Merged $namedFiles Enigma file(s): ${emitter.namedClasses} named classes, " +
                "${emitter.namedMembers} named members"
        )
        logger.lifecycle("Wrote ${file.name} (${file.readLines().size} lines)")
    }

    /**
     * Writes one element at a time to the tiny stream, joining the generated side to its named
     * counterpart and tallying what actually carried a name.
     *
     * Every element follows the same four beats — visit, intermediary name, named name, content —
     * and the writer may bail out at either visit, which skips whatever the element contains.
     */
    private class Emitter(private val writer: MappingWriter, private val toOfficial: Descriptors) {
        var namedClasses = 0
            private set
        var namedMembers = 0
            private set

        /** Returns whether the writer wants this class' members. */
        fun visitClass(official: String, intermediary: String, named: MappingTree.ClassMapping?): Boolean {
            if (!writer.visitClass(official)) return false
            writer.visitDstName(MappedElementKind.CLASS, 0, intermediary)
            named?.getDstName(0)?.let {
                namedClasses++
                writer.visitDstName(MappedElementKind.CLASS, 1, it)
            }
            if (!writer.visitElementContent(MappedElementKind.CLASS)) return false
            named?.comment?.let { writer.visitComment(MappedElementKind.CLASS, it) }
            return true
        }

        fun visitMember(
            kind: MappedElementKind,
            official: String,
            officialDesc: String?,
            intermediary: String,
            named: MappingTree.MemberMapping?,
        ) {
            val visited = when (kind) {
                MappedElementKind.FIELD -> writer.visitField(official, officialDesc)
                MappedElementKind.METHOD -> writer.visitMethod(official, officialDesc)
                else -> error("not a member kind: $kind")
            }
            if (!visited) return
            writer.visitDstName(kind, 0, intermediary)
            named?.getDstName(0)?.let {
                namedMembers++
                writer.visitDstName(kind, 1, it)
            }
            if (!writer.visitElementContent(kind)) return
            named?.comment?.let { writer.visitComment(kind, it) }
            if (named is MappingTree.MethodMapping) visitArgs(named)
        }

        /**
         * Members the store names but the generated file omits: their official name was already
         * readable, so official and intermediary agree and only the descriptor needs translating
         * back. Dropping them silently would lose work.
         */
        fun visitUnjoinedMembers(
            source: MappingTree.ClassMapping,
            seenFields: Set<String>,
            seenMethods: Set<String>,
        ) {
            for (field in source.fields.sortedWith(BY_SRC)) {
                if ("${field.srcName}${field.srcDesc}" in seenFields) continue
                val officialDesc = toOfficial.map(field.srcDesc) ?: continue
                visitMember(MappedElementKind.FIELD, field.srcName, officialDesc, field.srcName, field)
            }
            for (method in source.methods.sortedWith(BY_SRC)) {
                if ("${method.srcName}${method.srcDesc}" in seenMethods) continue
                val officialDesc = toOfficial.map(method.srcDesc) ?: continue
                visitMember(MappedElementKind.METHOD, method.srcName, officialDesc, method.srcName, method)
            }
        }

        private fun visitArgs(method: MappingTree.MethodMapping) {
            for (arg in method.args.sortedBy { it.lvIndex }) {
                if (!writer.visitMethodArg(arg.argPosition, arg.lvIndex, arg.srcName)) continue
                arg.getDstName(0)?.let { writer.visitDstName(MappedElementKind.METHOD_ARG, 1, it) }
                if (!writer.visitElementContent(MappedElementKind.METHOD_ARG)) continue
                arg.comment?.let { writer.visitComment(MappedElementKind.METHOD_ARG, it) }
            }
        }
    }

    /** Translates class names inside a descriptor through a class map. */
    private class Descriptors(private val classes: Map<String, String>) {
        fun map(desc: String?): String? = desc?.let {
            CLASS_IN_DESCRIPTOR.replace(it) { match -> "L${classes[match.groupValues[1]] ?: match.groupValues[1]};" }
        }
    }

    private companion object {
        const val OFFICIAL = "official"
        const val INTERMEDIARY = "intermediary"
        const val NAMED = "named"
        val CLASS_IN_DESCRIPTOR = Regex("""L([^;]+);""")

        /** Emission order for members, so the output file is stable across runs. */
        val BY_SRC: Comparator<MappingTree.MemberMapping> = compareBy({ it.srcName }, { it.srcDesc })
    }
}
