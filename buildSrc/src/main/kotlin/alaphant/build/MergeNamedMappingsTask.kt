package alaphant.build

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
 * Collapses the two halves of the mapping store into the single three-namespace tiny v2 file that
 * the remap tasks and the published `mappingsJar` consume.
 *
 * `official -> intermediary` comes from the generated tiny file; `intermediary -> named` comes from
 * the Enigma directory, where one file per top-level class is what lets agents work in parallel
 * without stepping on each other. The join key is the intermediary name, which is the whole point of
 * the three-namespace scheme: a Charles upgrade reissues the left column and leaves the right one
 * alone.
 *
 * Descriptors need translating, not just names — the Enigma store was authored against the
 * intermediary jar, so its member descriptors name intermediary types while the generated file's
 * name official ones.
 */
@CacheableTask
abstract class MergeNamedMappingsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val intermediaryMappings: RegularFileProperty

    /** Enigma directory format, one `.mapping` file per top-level class. Absent until Wave 1b runs. */
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

        var namedClasses = 0
        var namedMembers = 0

        val file = outputFile.get().asFile.also { it.parentFile?.mkdirs() }
        val tinyWriter: MappingWriter = checkNotNull(MappingWriter.create(file.toPath(), MappingFormat.TINY_2_FILE)) {
            "mapping-io has no tiny v2 writer"
        }
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
                if (!writer.visitClass(official)) continue
                writer.visitDstName(MappedElementKind.CLASS, 0, intermediaryName)
                namedCls?.getDstName(0)?.let {
                    namedClasses++
                    writer.visitDstName(MappedElementKind.CLASS, 1, it)
                }
                if (!writer.visitElementContent(MappedElementKind.CLASS)) continue
                namedCls?.comment?.let { writer.visitComment(MappedElementKind.CLASS, it) }

                val seenFields = HashSet<String>()
                for (field in cls.fields.sortedWith(compareBy({ it.srcName }, { it.srcDesc }))) {
                    val intermediaryMember = field.getDstName(0) ?: field.srcName
                    val intermediaryDesc = toIntermediary.map(field.srcDesc)
                    seenFields.add("$intermediaryMember$intermediaryDesc")
                    val namedField = namedCls?.getField(intermediaryMember, intermediaryDesc)
                    if (!writer.visitField(field.srcName, field.srcDesc)) continue
                    writer.visitDstName(MappedElementKind.FIELD, 0, intermediaryMember)
                    namedField?.getDstName(0)?.let {
                        namedMembers++
                        writer.visitDstName(MappedElementKind.FIELD, 1, it)
                    }
                    if (!writer.visitElementContent(MappedElementKind.FIELD)) continue
                    namedField?.comment?.let { writer.visitComment(MappedElementKind.FIELD, it) }
                }

                val seenMethods = HashSet<String>()
                for (method in cls.methods.sortedWith(compareBy({ it.srcName }, { it.srcDesc }))) {
                    val intermediaryMember = method.getDstName(0) ?: method.srcName
                    val intermediaryDesc = toIntermediary.map(method.srcDesc)
                    seenMethods.add("$intermediaryMember$intermediaryDesc")
                    val namedMethod = namedCls?.getMethod(intermediaryMember, intermediaryDesc)
                    if (!writer.visitMethod(method.srcName, method.srcDesc)) continue
                    writer.visitDstName(MappedElementKind.METHOD, 0, intermediaryMember)
                    namedMethod?.getDstName(0)?.let {
                        namedMembers++
                        writer.visitDstName(MappedElementKind.METHOD, 1, it)
                    }
                    if (!writer.visitElementContent(MappedElementKind.METHOD)) continue
                    namedMethod?.comment?.let { writer.visitComment(MappedElementKind.METHOD, it) }
                    namedMethod?.let { writeArgs(writer, it) }
                }

                // Members the Enigma store names but the generated file does not carry, i.e. members
                // whose official name was already readable. Dropping these silently would lose work.
                namedCls?.let { source ->
                    for (field in source.fields.sortedWith(compareBy({ it.srcName }, { it.srcDesc }))) {
                        if ("${field.srcName}${field.srcDesc}" in seenFields) continue
                        val officialDesc = toOfficial.map(field.srcDesc) ?: continue
                        if (!writer.visitField(field.srcName, officialDesc)) continue
                        writer.visitDstName(MappedElementKind.FIELD, 0, field.srcName)
                        field.getDstName(0)?.let {
                            namedMembers++
                            writer.visitDstName(MappedElementKind.FIELD, 1, it)
                        }
                        if (!writer.visitElementContent(MappedElementKind.FIELD)) continue
                        field.comment?.let { writer.visitComment(MappedElementKind.FIELD, it) }
                    }
                    for (method in source.methods.sortedWith(compareBy({ it.srcName }, { it.srcDesc }))) {
                        if ("${method.srcName}${method.srcDesc}" in seenMethods) continue
                        val officialDesc = toOfficial.map(method.srcDesc) ?: continue
                        if (!writer.visitMethod(method.srcName, officialDesc)) continue
                        writer.visitDstName(MappedElementKind.METHOD, 0, method.srcName)
                        method.getDstName(0)?.let {
                            namedMembers++
                            writer.visitDstName(MappedElementKind.METHOD, 1, it)
                        }
                        if (!writer.visitElementContent(MappedElementKind.METHOD)) continue
                        method.comment?.let { writer.visitComment(MappedElementKind.METHOD, it) }
                        writeArgs(writer, method)
                    }
                }
            }

            // Classes the Enigma store names but the generated file does not carry: a class whose
            // official name was already readable, renamed or commented anyway.
            for (cls in named.classes.sortedBy { it.srcName }) {
                if (cls.srcName in emitted) continue
                if (!writer.visitClass(cls.srcName)) continue
                writer.visitDstName(MappedElementKind.CLASS, 0, cls.srcName)
                cls.getDstName(0)?.let {
                    namedClasses++
                    writer.visitDstName(MappedElementKind.CLASS, 1, it)
                }
                if (!writer.visitElementContent(MappedElementKind.CLASS)) continue
                cls.comment?.let { writer.visitComment(MappedElementKind.CLASS, it) }

                for (field in cls.fields.sortedWith(compareBy({ it.srcName }, { it.srcDesc }))) {
                    val officialDesc = toOfficial.map(field.srcDesc) ?: continue
                    if (!writer.visitField(field.srcName, officialDesc)) continue
                    writer.visitDstName(MappedElementKind.FIELD, 0, field.srcName)
                    field.getDstName(0)?.let {
                        namedMembers++
                        writer.visitDstName(MappedElementKind.FIELD, 1, it)
                    }
                    if (!writer.visitElementContent(MappedElementKind.FIELD)) continue
                    field.comment?.let { writer.visitComment(MappedElementKind.FIELD, it) }
                }
                for (method in cls.methods.sortedWith(compareBy({ it.srcName }, { it.srcDesc }))) {
                    val officialDesc = toOfficial.map(method.srcDesc) ?: continue
                    if (!writer.visitMethod(method.srcName, officialDesc)) continue
                    writer.visitDstName(MappedElementKind.METHOD, 0, method.srcName)
                    method.getDstName(0)?.let {
                        namedMembers++
                        writer.visitDstName(MappedElementKind.METHOD, 1, it)
                    }
                    if (!writer.visitElementContent(MappedElementKind.METHOD)) continue
                    method.comment?.let { writer.visitComment(MappedElementKind.METHOD, it) }
                    writeArgs(writer, method)
                }
            }

            writer.visitEnd()
        }

        logger.lifecycle("Merged $namedFiles Enigma file(s): $namedClasses named classes, $namedMembers named members")
        logger.lifecycle("Wrote ${file.name} (${file.readLines().size} lines)")
    }

    private fun writeArgs(writer: MappingWriter, method: MappingTree.MethodMapping) {
        for (arg in method.args.sortedBy { it.lvIndex }) {
            if (!writer.visitMethodArg(arg.argPosition, arg.lvIndex, arg.srcName)) continue
            arg.getDstName(0)?.let { writer.visitDstName(MappedElementKind.METHOD_ARG, 1, it) }
            if (!writer.visitElementContent(MappedElementKind.METHOD_ARG)) continue
            arg.comment?.let { writer.visitComment(MappedElementKind.METHOD_ARG, it) }
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
    }
}
