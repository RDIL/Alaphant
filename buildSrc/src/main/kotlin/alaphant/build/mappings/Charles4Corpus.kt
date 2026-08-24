package alaphant.build.mappings

import alaphant.build.tasks.mappings.Jar
import org.objectweb.asm.tree.ClassNode
import java.io.File

/**
 * The Charles 4 naming corpus, `mappings/legacy/charles4.tiny` (tiny v1).
 *
 * It lists only members somebody named, so counts off it are a lower bound. Of its 2276 `CLASS`
 * lines just 703 carry a real name — the other 1562 repeat the obfuscated name back, and are kept
 * only because their members were often named anyway.
 */
internal class Charles4Corpus(
    val entries: List<Entry>,
    /** Charles 4 official -> Charles 4 named, needed to read its own descriptors. */
    val classMap: Map<String, String>,
) {
    data class Member(val official: String, val named: String, val desc: String)

    data class Entry(
        val official: String,
        val named: String,
        /** Whether [named] is a real name rather than the obfuscated name repeated back. */
        val hasName: Boolean,
        val fields: MutableList<Member> = mutableListOf(),
        val methods: MutableList<Member> = mutableListOf(),
    ) {
        val namedMembers: Int get() = fields.size + methods.size
    }

    companion object {
        fun read(file: File): Charles4Corpus {
            val byOfficial = LinkedHashMap<String, Entry>()
            val classMap = HashMap<String, String>()

            for (raw in file.readLines()) {
                val parts = raw.split('\t')
                when (parts.firstOrNull()) {
                    "CLASS" -> {
                        if (parts.size < 3 || parts[2].isEmpty()) continue
                        classMap[parts[1]] = parts[2]
                        byOfficial[parts[1]] = Entry(parts[1], parts[2], hasName = isRealName(parts[1], parts[2]))
                    }
                    "FIELD", "METHOD" -> {
                        if (parts.size < 5 || parts[4].isEmpty()) continue
                        val owner = byOfficial[parts[1]] ?: continue
                        val member = Member(official = parts[3], named = parts[4], desc = parts[2])
                        if (parts[0] == "FIELD") owner.fields.add(member) else owner.methods.add(member)
                    }
                }
            }

            return Charles4Corpus(byOfficial.values.toList(), classMap)
        }

        /** An identity entry: same simple name with only the package renamed, or a name under 3 chars. */
        private fun isRealName(official: String, named: String): Boolean {
            val simple = named.substringAfterLast('/')
            return simple != official.substringAfterLast('/') && simple.length >= 3
        }
    }
}

/**
 * Puts Charles 4 and Charles 5 descriptors into one comparable form: types readable in Charles 5 are
 * kept, everything else collapses to `?` — `(Lcom/charlesproxy/model/Transaction;L?;)V`. That much
 * is stable across the two releases and enough to tell two classes in a package apart.
 */
internal class DescriptorTranslator(
    private val corpus: Charles4Corpus,
    private val jar: Jar,
    private val obfuscatedIn5: Set<String>,
) {
    private val translated = HashMap<String, String>()
    private val shaped = HashMap<String, String>()

    /** A Charles 4 descriptor, in comparable form. */
    fun translate(desc: String): String = translated.getOrPut(desc) {
        CLASS_IN_DESCRIPTOR.replace(desc) { match ->
            val charles4 = match.groupValues[1]
            val named4 = corpus.classMap[charles4]
                ?: if (isObfuscatedIn4(charles4)) return@replace UNKNOWN else charles4
            val name5 = Recovery.charles5Name(named4)
            if (name5 in jar.byName && name5 !in obfuscatedIn5) "L$name5;" else UNKNOWN
        }
    }

    /** A Charles 5 descriptor, in the same comparable form. */
    fun shape(desc: String): String = shaped.getOrPut(desc) {
        CLASS_IN_DESCRIPTOR.replace(desc) { match ->
            val name = match.groupValues[1]
            if (name in obfuscatedIn5) UNKNOWN else "L$name;"
        }
    }

    /** Every member descriptor of a Charles 5 class, in comparable form, split by kind. */
    fun shapeOf(node: ClassNode): Shape = Shape(
        fields = node.fields.orEmpty().map { shape(it.desc) },
        methods = node.methods.orEmpty()
            .filter { it.name != "<init>" && it.name != "<clinit>" }
            .map { shape(it.desc) },
    )

    class Shape(val fields: List<String>, val methods: List<String>)

    /** Multiset intersection of member descriptors; ones with no surviving type score half. */
    fun score(candidate: Charles4Corpus.Entry, shape: Shape): Int {
        var score = 0
        score += intersect(candidate.fields.map { translate(it.desc) }, shape.fields)
        score += intersect(candidate.methods.map { translate(it.desc) }, shape.methods)
        return score
    }

    private fun intersect(left: List<String>, right: List<String>): Int {
        val counts = HashMap<String, Int>()
        for (desc in left) counts[desc] = (counts[desc] ?: 0) + 1
        var score = 0
        for (desc in right) {
            val remaining = counts[desc] ?: 0
            if (remaining == 0) continue
            counts[desc] = remaining - 1
            score += if (informative(desc)) 2 else 1
        }
        return score
    }

    /** Does this descriptor still name a type we can pin, rather than being all `?` and primitives? */
    private fun informative(desc: String): Boolean =
        CLASS_IN_DESCRIPTOR.findAll(desc).any { it.value != UNKNOWN }

    /** Charles 4 obfuscated simple names are one or two lower-case letters (`com/google/protobuf/a`). */
    private fun isObfuscatedIn4(internalName: String): Boolean {
        val simple = internalName.substringAfterLast('/').substringAfterLast('$')
        return simple.length <= 2 && simple.all { it.isLowerCase() }
    }

    private companion object {
        const val UNKNOWN = "L?;"
        val CLASS_IN_DESCRIPTOR = Regex("""L([^;]+);""")
    }
}
