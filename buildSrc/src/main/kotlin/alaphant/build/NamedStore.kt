package alaphant.build

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.format.enigma.EnigmaDirReader
import net.fabricmc.mappingio.format.enigma.EnigmaDirWriter
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.io.File

/**
 * In-memory model of `mappings/named/` — the `intermediary -> named` half of the store.
 *
 * Read and written through mapping-io's Enigma directory support, so the files this produces are
 * byte-for-byte the shape Enigma itself writes: laid out by *named* name where one exists, by
 * intermediary name otherwise. That matters because Enigma saving over a generated tree would
 * otherwise leave a second copy of every renamed class behind.
 *
 * Generated content is marked so regenerating is idempotent: [BOOTSTRAP_MARKER] comment lines are
 * dropped on load and re-added by whatever pass still believes them, and a name a human has since
 * changed is never overwritten.
 */
internal class NamedStore private constructor(
    private val classes: MutableMap<String, ClassNames>,
) {
    class ClassNames(val intermediary: String) {
        var name: String? = null
        var generated: Boolean = false
        val comments = mutableListOf<String>()
        val fields = LinkedHashMap<MemberKey, MemberNames>()
        val methods = LinkedHashMap<MemberKey, MemberNames>()
    }

    data class MemberKey(val name: String, val desc: String)

    class MemberNames {
        var name: String? = null
        var generated: Boolean = false
        val comments = mutableListOf<String>()
        /** `lvIndex -> name`, as Enigma's `ARG` lines. */
        val args = LinkedHashMap<Int, String>()
    }

    fun forClass(intermediary: String): ClassNames = classes.getOrPut(intermediary) { ClassNames(intermediary) }

    fun existing(intermediary: String): ClassNames? = classes[intermediary]

    private val claimedNames: MutableSet<String> = classes.values.mapNotNullTo(HashSet()) { it.name }

    /**
     * Records a name, unless the element already has one or the name is already taken.
     *
     * The uniqueness check is not paranoia: two classes sharing a named name land in the same Enigma
     * file, collide in the remapped jar, and are invisible in a diff. Returns whether it landed.
     *
     * The evidence comment is written either way. Naming and explaining are separate things: a pass
     * that runs a second time finds the name already there and must still restate its reasoning,
     * because [load] stripped the previous copy to keep regeneration idempotent. Skipping it there is
     * how every generated comment in the store quietly disappears on the second run.
     */
    fun name(intermediary: String, name: String, comment: String): Boolean {
        val cls = forClass(intermediary)
        cls.comments.add("$BOOTSTRAP_MARKER$comment")
        if (cls.name != null) return false
        if (!claimedNames.add(name)) return false
        cls.name = name
        cls.generated = true
        return true
    }

    fun comment(intermediary: String, comment: String) {
        forClass(intermediary).comments.add("$BOOTSTRAP_MARKER$comment")
    }

    fun nameMethod(owner: String, key: MemberKey, name: String, comment: String): Boolean =
        nameMember(forClass(owner).methods.getOrPut(key) { MemberNames() }, name, comment)

    fun nameField(owner: String, key: MemberKey, name: String, comment: String): Boolean =
        nameMember(forClass(owner).fields.getOrPut(key) { MemberNames() }, name, comment)

    private fun nameMember(member: MemberNames, name: String, comment: String): Boolean {
        member.comments.add("$BOOTSTRAP_MARKER$comment")
        if (member.name != null) return false
        member.name = name
        member.generated = true
        return true
    }

    val namedClassCount: Int get() = classes.values.count { it.name != null }
    val namedMemberCount: Int
        get() = classes.values.sumOf { cls -> cls.fields.values.count { it.name != null } + cls.methods.values.count { it.name != null } }

    /** Names already in use, so a proposal cannot collide with a name a human chose. */
    fun takenClassNames(): Set<String> = classes.values.mapNotNull { it.name }.toSet()

    fun write(dir: File) {
        dir.mkdirs()
        EnigmaDirWriter(dir.toPath(), true).use { writer ->
            writer.visitHeader()
            writer.visitNamespaces(INTERMEDIARY, listOf(NAMED))
            writer.visitContent()

            for (cls in classes.values.sortedBy { it.intermediary }) {
                if (cls.isEmpty()) continue
                if (!writer.visitClass(cls.intermediary)) continue
                cls.name?.let { writer.visitDstName(MappedElementKind.CLASS, 0, it) }
                if (!writer.visitElementContent(MappedElementKind.CLASS)) continue
                cls.comments.joined()?.let { writer.visitComment(MappedElementKind.CLASS, it) }

                for ((key, member) in cls.fields.entries.sortedBy { "${it.key.name}${it.key.desc}" }) {
                    if (member.isEmpty()) continue
                    if (!writer.visitField(key.name, key.desc)) continue
                    member.name?.let { writer.visitDstName(MappedElementKind.FIELD, 0, it) }
                    if (!writer.visitElementContent(MappedElementKind.FIELD)) continue
                    member.comments.joined()?.let { writer.visitComment(MappedElementKind.FIELD, it) }
                }

                for ((key, member) in cls.methods.entries.sortedBy { "${it.key.name}${it.key.desc}" }) {
                    if (member.isEmpty()) continue
                    if (!writer.visitMethod(key.name, key.desc)) continue
                    member.name?.let { writer.visitDstName(MappedElementKind.METHOD, 0, it) }
                    if (!writer.visitElementContent(MappedElementKind.METHOD)) continue
                    member.comments.joined()?.let { writer.visitComment(MappedElementKind.METHOD, it) }
                    for ((lvIndex, argName) in member.args) {
                        if (!writer.visitMethodArg(lvIndex, lvIndex, null)) continue
                        writer.visitDstName(MappedElementKind.METHOD_ARG, 0, argName)
                        writer.visitElementContent(MappedElementKind.METHOD_ARG)
                    }
                }
            }

            writer.visitEnd()
        }
    }

    private fun ClassNames.isEmpty(): Boolean =
        name == null && comments.isEmpty() &&
            fields.values.all { it.isEmpty() } && methods.values.all { it.isEmpty() }

    private fun MemberNames.isEmpty(): Boolean = name == null && comments.isEmpty() && args.isEmpty()

    private fun List<String>.joined(): String? = takeIf { it.isNotEmpty() }?.joinToString("\n")

    companion object {
        const val INTERMEDIARY = "intermediary"
        const val NAMED = "named"

        /**
         * Prefixes every comment line a bootstrap pass wrote. Stripped on load, so re-running the
         * bootstrap refreshes its own reasoning without piling up duplicates or touching a comment a
         * human typed.
         */
        const val BOOTSTRAP_MARKER = "[bootstrap] "

        fun load(dir: File): NamedStore {
            val store = NamedStore(LinkedHashMap())
            if (!dir.isDirectory || dir.walkTopDown().none { it.isFile && it.extension == "mapping" }) return store

            val tree = MemoryMappingTree()
            EnigmaDirReader.read(dir.toPath(), INTERMEDIARY, NAMED, tree)

            for (cls in tree.classes) {
                val entry = store.forClass(cls.srcName)
                entry.name = cls.getDstName(0)
                entry.comments.addAll(cls.comment.humanLines())

                for (field in cls.fields) {
                    val member = entry.fields.getOrPut(MemberKey(field.srcName, field.srcDesc ?: continue)) { MemberNames() }
                    member.name = field.getDstName(0)
                    member.comments.addAll(field.comment.humanLines())
                }
                for (method in cls.methods) {
                    val member = entry.methods.getOrPut(MemberKey(method.srcName, method.srcDesc ?: continue)) { MemberNames() }
                    member.name = method.getDstName(0)
                    member.comments.addAll(method.comment.humanLines())
                    for (arg in method.args) {
                        arg.getDstName(0)?.let { member.args[arg.lvIndex] = it }
                    }
                }
            }

            return store
        }

        private fun String?.humanLines(): List<String> =
            this?.lineSequence()?.filterNot { it.startsWith(BOOTSTRAP_MARKER) }?.filter { it.isNotBlank() }?.toList()
                ?: emptyList()
    }
}
