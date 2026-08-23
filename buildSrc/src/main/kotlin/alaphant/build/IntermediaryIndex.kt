package alaphant.build

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.io.File

/**
 * The generated `official -> intermediary` file, in the shape the bootstrap passes need.
 *
 * Everything written into `mappings/named/` is keyed on **intermediary** names and **intermediary**
 * descriptors, because that is what Enigma sees when it opens the intermediary jar. The bootstrap
 * reads the official jar, so every element it finds has to be translated on the way in — keying a
 * generated mapping on an official name produces a file that looks right, parses, and matches
 * nothing.
 */
internal class IntermediaryIndex private constructor(
    private val classes: Map<String, String>,
    private val fields: Map<String, Member>,
    private val methods: Map<String, Member>,
) {
    /** An element's intermediary name paired with its intermediary descriptor. */
    data class Member(val name: String, val desc: String)

    fun classOf(official: String): String? = classes[official]

    val classMap: Map<String, String> get() = classes

    fun field(owner: String, name: String, desc: String): Member? = fields["$owner.$name:$desc"]

    fun method(owner: String, name: String, desc: String): Member? = methods["$owner.$name:$desc"]

    /** Translates an official descriptor into the intermediary namespace. */
    fun mapDescriptor(desc: String): String = CLASS_IN_DESCRIPTOR.replace(desc) { match ->
        "L${classes[match.groupValues[1]] ?: match.groupValues[1]};"
    }

    companion object {
        private val CLASS_IN_DESCRIPTOR = Regex("""L([^;]+);""")

        fun read(file: File): IntermediaryIndex {
            val tree = MemoryMappingTree()
            MappingReader.read(file.toPath(), MappingFormat.TINY_2_FILE, tree)

            val classes = tree.classes.associate { it.srcName to (it.getDstName(0) ?: it.srcName) }
            val index = IntermediaryIndex(classes, emptyMap(), emptyMap())

            val fields = HashMap<String, Member>()
            val methods = HashMap<String, Member>()
            for (cls in tree.classes) {
                for (field in cls.fields) {
                    val desc = field.srcDesc ?: continue
                    fields["${cls.srcName}.${field.srcName}:$desc"] =
                        Member(field.getDstName(0) ?: field.srcName, index.mapDescriptor(desc))
                }
                for (method in cls.methods) {
                    val desc = method.srcDesc ?: continue
                    methods["${cls.srcName}.${method.srcName}:$desc"] =
                        Member(method.getDstName(0) ?: method.srcName, index.mapDescriptor(desc))
                }
            }

            return IntermediaryIndex(classes, fields, methods)
        }
    }
}
