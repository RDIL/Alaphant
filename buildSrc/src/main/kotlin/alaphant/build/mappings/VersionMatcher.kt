package alaphant.build.mappings

import alaphant.build.tasks.mappings.Jar
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/** The key shapes the ledger records elements under, so a match can be looked up straight against it. */
internal object Keys {
    fun field(owner: String, name: String, desc: String): String = "$owner.$name:$desc"
    fun method(owner: String, name: String, desc: String): String = MethodGroups.key(owner, name, desc)
}

/** A member as its owner names it, before any translation. */
internal data class Ref(val owner: String, val name: String, val desc: String)

/** A `new` of some class, and where in the constructing method it happens. */
internal data class Creation(val site: Ref, val ordinal: Int)

/**
 * One version's jar with the indexes matching needs, computed once.
 *
 * Everything here is deliberately *name-independent* where the obfuscator is concerned: opcode
 * sequences, string constants, reference edges and member descriptors all survive a rename, which is
 * exactly why they can identify the same element in two releases that share no obfuscated names.
 */
internal class MatchSide(jar: Jar, val classifier: NameClassifier) {
    val nodes: List<ClassNode> = jar.nodes
    val byName: Map<String, ClassNode> = jar.byName
    val groups: MethodGroups = MethodGroups.compute(nodes, classifier)

    /** Distinct string constants per class, sorted. Charles' UI text barely moves between patches. */
    val strings: Map<String, List<String>> = nodes.associate { it.name to stringsOf(it) }

    /** The class' own original name, where its Lombok logger states it. */
    val loggerLiterals: Map<String, String> = nodes.mapNotNull { node ->
        Slf4j.literalOf(node)?.let { node.name to it }
    }.toMap()

    /** In-jar types each class mentions. */
    val outgoing: Map<String, Set<String>> = nodes.associate { it.name to referencesOf(it) }

    /** The reverse of [outgoing]: who mentions this class. */
    val incoming: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for ((from, targets) in outgoing) for (target in targets) getOrPut(target) { LinkedHashSet() }.add(from)
    }

    /** Outer class -> the nested classes declared inside it, as the jar spells them. */
    val inners: Map<String, List<String>> = nodes.map { it.name }
        .filter { '$' in it.substringAfterLast('/') }
        .groupBy { it.substring(0, it.lastIndexOf('$')) }

    private val codeIndex = HashMap<String, Code>()
    private val fieldUseIndex = HashMap<String, MutableSet<Ref>>()
    private val creationIndex = HashMap<String, MutableList<Creation>>()

    /** Method key -> what its body does, with nothing name-dependent baked in. */
    val code: Map<String, Code> get() = codeIndex

    /** Field key -> the methods that read or write it. */
    val fieldUses: Map<String, Set<Ref>> get() = fieldUseIndex

    /** Class -> every `new` of it, with its position among the `new`s of the method doing it. */
    val creations: Map<String, List<Creation>> get() = creationIndex

    class Code(
        /** Hash of the opcode sequence. Renaming cannot touch it; editing the method will. */
        val shape: Long,
        val strings: List<String>,
        val calls: List<Ref>,
        val fields: List<Ref>,
    )

    init {
        for (node in nodes) {
            for (method in node.methods.orEmpty()) {
                val instructions = method.instructions ?: continue
                val self = Ref(node.name, method.name, method.desc)
                var shape = 1L
                val text = ArrayList<String>()
                val calls = ArrayList<Ref>()
                val fields = ArrayList<Ref>()

                var news = 0
                for (insn in instructions) {
                    if (insn.opcode >= 0) shape = shape * 31 + insn.opcode
                    when (insn) {
                        is TypeInsnNode -> if (insn.opcode == Opcodes.NEW) {
                            creationIndex.getOrPut(insn.desc) { mutableListOf() }.add(Creation(self, news++))
                        }
                        is LdcInsnNode -> (insn.cst as? String)?.let(text::add)
                        is MethodInsnNode -> calls.add(Ref(insn.owner, insn.name, insn.desc))
                        is FieldInsnNode -> {
                            val ref = Ref(insn.owner, insn.name, insn.desc)
                            fields.add(ref)
                            if (ref.owner in byName) {
                                fieldUseIndex.getOrPut(Keys.field(ref.owner, ref.name, ref.desc)) { LinkedHashSet() }
                                    .add(self)
                            }
                        }
                    }
                }

                codeIndex[Keys.method(node.name, method.name, method.desc)] = Code(shape, text, calls, fields)
            }
        }
    }

    private fun stringsOf(node: ClassNode): List<String> {
        val out = sortedSetOf<String>()
        for (method in node.methods.orEmpty()) {
            val instructions = method.instructions ?: continue
            for (insn in instructions) if (insn is LdcInsnNode) (insn.cst as? String)?.let(out::add)
        }
        for (field in node.fields.orEmpty()) (field.value as? String)?.let(out::add)
        return out.toList()
    }

    private fun referencesOf(node: ClassNode): Set<String> {
        val out = LinkedHashSet<String>()
        fun type(name: String?) {
            if (name != null && name != node.name && name in byName) out.add(name)
        }
        fun desc(value: String?) {
            if (value != null) for (match in DESCRIPTOR_CLASS.findAll(value)) type(match.groupValues[1])
        }

        type(node.superName)
        node.interfaces.orEmpty().forEach(::type)
        node.fields.orEmpty().forEach { desc(it.desc) }
        for (method in node.methods.orEmpty()) {
            desc(method.desc)
            val instructions = method.instructions ?: continue
            for (insn in instructions) when (insn) {
                is TypeInsnNode -> if (!insn.desc.startsWith("[")) type(insn.desc) else desc(insn.desc)
                is MethodInsnNode -> { type(insn.owner); desc(insn.desc) }
                is FieldInsnNode -> { type(insn.owner); desc(insn.desc) }
                is LdcInsnNode -> (insn.cst as? Type)?.let { desc(it.descriptor) }
            }
        }
        return out
    }

    companion object {
        val DESCRIPTOR_CLASS = Regex("""L([^;]+);""")
    }
}

/** What [VersionMatcher] worked out, keyed the way the ledger records elements. */
internal class MatchResult(
    val classes: Map<String, String>,
    val fields: Map<String, String>,
    val methods: Map<String, String>,
    /** Matches per pass, in the order the passes ran. */
    val classPasses: Map<String, Int>,
    val memberPasses: Map<String, Int>,
    /** Old member key -> the pass that matched it, for tracing a match back to its evidence. */
    val provenance: Map<String, String>,
    /** Pairs a pass proposed and [VersionMatcher] refused as implausible. See `VersionMatcher.plausible`. */
    val rejected: Int,
    val rounds: Int,
    /** Why each unmatched old class stayed unmatched. See [VersionMatcher.diagnose]. */
    val unmatched: Map<String, String>,
)

/**
 * Works out which element of a new Charles jar is which element of the old one, so the intermediary
 * IDs — and with them every name in `mappings/named/` — carry across the upgrade.
 *
 * ### Why this is not a fingerprint lookup
 *
 * `mappings/ledger.json` already stores a shape fingerprint per class, and the obvious plan is to
 * hash the new jar the same way and join on it. That does not work, and it is worth being clear
 * about why: the stored hash is taken over *intermediary* names, so an unchanged class hashes
 * differently on the two sides as soon as its supertype or any parameter type is itself an unmatched
 * obfuscated class. The hash is version-stable only for classes whose entire shape is spelled in
 * readable or JDK types — the outer shell of the jar, and nothing behind it.
 *
 * So matching has to be **anchored and iterative**. Start from the elements the obfuscator could not
 * touch, express every shape in terms of what is matched *so far*, take only the matches that are
 * unambiguous under that evidence, and go round again with a larger anchor set. Each round sharpens
 * the next round's fingerprints. It stops when a full round adds nothing.
 *
 * ### The rule every pass obeys
 *
 * A pass matches `old -> new` only when the key it computes lands on **exactly one** unmatched class
 * on each side. Never a best guess, never a similarity score. A wrong match is materially worse than
 * no match: it silently moves a hand-written name onto the wrong element, where a missing match only
 * costs a fresh ID and a name that has to be worked out again. Everything unmatched is reported, and
 * `generateIntermediary` simply allocates it a new ID.
 *
 * ### Passes
 *
 * Anchors, run once:
 *
 * | Pass | Evidence |
 * | --- | --- |
 * | stable name | The name is readable end to end, so the obfuscator never touched it. |
 * | logger literal | The class states its own original name in a Lombok `@Slf4j` constant. |
 *
 * Then, each round, most specific first — a longer key means smaller buckets, so more singletons and
 * more matches, each backed by more evidence. The coarse passes still earn their place afterwards,
 * for the classes that changed enough that their combined key no longer agrees across versions:
 *
 * | Pass | Evidence |
 * | --- | --- |
 * | name pattern | The readable parts of the name, with obfuscated packages resolved by earlier rounds. |
 * | everything | Shape, bodies, strings and neighbours in one key. Does the bulk of the work. |
 * | shape | Supertypes and member descriptors in matched terms; strict first, then allowing unknowns. |
 * | strings | An identical, non-trivial set of string constants. |
 * | code | What the class' methods compile to, opcode by opcode. |
 * | neighbourhood | The same matched classes referring to it, and referred to by it. |
 * | construction site | Where it is `new`ed, and at which `new` of that method. |
 * | nested | The outer class is matched and the inner is the only candidate left inside it. |
 * | type propagation | Matched members' descriptors line up, so their unmatched types line up too. |
 *
 * Members are then matched inside each matched class pair, by readable name, descriptor, body, field
 * usage, and — only where a descriptor bucket is the same size on both sides — declaration order.
 *
 * Two invariants hold over every pass whatever proposed it: a class does not change kind, and it does
 * not move to a different part of the package tree. See `plausible`.
 */
internal class VersionMatcher(
    private val old: MatchSide,
    private val new: MatchSide,
    /**
     * Whether members that nothing else separates may be matched by declaration order.
     *
     * Only ever applies inside an already-matched class, to members sharing one descriptor, when both
     * sides have the same number of them. Charles' compiler emits members in declaration order and a
     * patch release rarely reshuffles it, so this is usually right — but it is the one pass with no
     * hard evidence behind it, so the report counts it separately.
     */
    private val positional: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    private val classifier = old.classifier

    private val classOldToNew = LinkedHashMap<String, String>()
    private val classNewToOld = HashMap<String, String>()
    private val fieldOldToNew = LinkedHashMap<String, String>()
    private val methodOldToNew = LinkedHashMap<String, String>()

    /** Descriptors of matched member pairs, which is what type propagation reads. */
    private val matchedDescriptors = ArrayList<Pair<String, String>>()

    private val classPasses = LinkedHashMap<String, Int>()
    private val memberPasses = LinkedHashMap<String, Int>()
    private val provenance = HashMap<String, String>()
    private var rejected = 0

    /** Old package path -> new package path, re-derived from the class matches every round. */
    private var packages: Map<String, String> = emptyMap()

    /** The image of [methodOldToNew], so a new-side method can be recognised as matched. */
    private var matchedMethods: Set<String> = emptySet()

    /**
     * The previous round's member matches, which is what lets a member reference be rendered as a
     * token instead of erased.
     *
     * Erasing was the obvious thing and it is badly lossy: three `()I` getters differing only in which
     * field they read all fingerprint identically, because the field's name is obfuscated and its
     * descriptor is `I` for all three. Rendering the reference as the member it was matched to breaks
     * the tie, and the circularity is the same anchored iteration the class passes already run on —
     * each round's member matches sharpen the next round's bodies.
     */
    private var priorFields: Map<String, String> = emptyMap()
    private var priorMethods: Map<String, String> = emptyMap()
    private var priorFieldImages: Set<String> = emptySet()
    private var priorMethodImages: Set<String> = emptySet()

    fun match(): MatchResult {
        anchorStableNames()
        anchorLoggerLiterals()
        log("  anchors: ${classOldToNew.size} classes")

        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            var gained = classRound()
            matchMembers()
            matchedMethods = methodOldToNew.values.toHashSet()
            gained += propagateTypes()
            log("  round $rounds: +$gained classes, ${classOldToNew.size} of ${old.nodes.size} matched")
            if (gained == 0) break
        }

        // Member fingerprints read the previous round's member matches, so the last round leaves
        // recall on the table. Settle them against the final anchor set.
        var previous = -1
        while (fieldOldToNew.size + methodOldToNew.size > previous) {
            previous = fieldOldToNew.size + methodOldToNew.size
            matchMembers()
        }

        return MatchResult(
            classes = classOldToNew,
            fields = fieldOldToNew,
            methods = methodOldToNew,
            classPasses = classPasses,
            memberPasses = memberPasses,
            provenance = provenance,
            rejected = rejected,
            rounds = rounds,
            unmatched = diagnose(),
        )
    }

    /**
     * Why the classes that did not match did not match, which is the difference between "the release
     * deleted it" and "the matcher cannot tell two things apart".
     *
     * A tie is the honest answer, not a failure: two classes with the same supertype, the same member
     * descriptors, the same bodies and the same neighbours are the same class as far as any evidence
     * in the jar goes, and picking one would be a coin toss with a hand-written name riding on it.
     */
    private fun diagnose(): Map<String, String> {
        val newByKey = HashMap<String, Int>()
        for (node in new.nodes) {
            if (node.name in classNewToOld) continue
            newByKey.merge(fullKey(node, new, isOldSide = false) ?: continue, 1, Int::plus)
        }
        val oldByKey = HashMap<String, Int>()
        for (node in old.nodes) {
            if (node.name in classOldToNew) continue
            oldByKey.merge(fullKey(node, old, isOldSide = true) ?: continue, 1, Int::plus)
        }

        val out = LinkedHashMap<String, String>()
        for (node in old.nodes) {
            if (node.name in classOldToNew) continue
            val key = fullKey(node, old, isOldSide = true)
            val candidates = key?.let { newByKey[it] } ?: 0
            out[node.name] = when {
                candidates == 0 -> "no counterpart in the new jar"
                else -> "${oldByKey[key] ?: 1}-to-$candidates tie on identical evidence"
            }
        }
        return out
    }

    // -- accepting a match ------------------------------------------------------------------------

    private fun accept(oldName: String, newName: String, pass: String): Boolean {
        if (oldName in classOldToNew || newName in classNewToOld) return false
        if (!plausible(old.byName.getValue(oldName), new.byName.getValue(newName))) {
            rejected++
            return false
        }
        classOldToNew[oldName] = newName
        classNewToOld[newName] = oldName
        classPasses.merge(pass, 1, Int::plus)
        return true
    }

    /**
     * The two invariants a patch release does not break, checked on every match whatever pass proposed
     * it: a class does not turn into an interface, an enum or a record, and it does not move to a
     * different part of the package tree.
     *
     * The second one is what stops the failure churn actually produces. A deleted class and a
     * surviving one can key identically once an edit has perturbed the survivor's own key, and the
     * deleted one then claims the survivor's slot — a wrong match, and the expensive kind, because it
     * carries a name with it. Requiring the readable package skeleton to agree rules that out
     * wholesale, since readable segments are the ones the obfuscator never touched.
     *
     * The cost is a class that genuinely moves package, which shows up in the report as a cluster of
     * misses in one package rather than as a silently misplaced name.
     */
    private fun plausible(a: ClassNode, b: ClassNode): Boolean {
        if ((a.access and KIND_MASK) != (b.access and KIND_MASK)) return false
        return skeleton(a.name) == skeleton(b.name)
    }

    /** The package path with its obfuscated segments blanked out. */
    private fun skeleton(internalName: String): String =
        internalName.substringBeforeLast('/', "").split('/')
            .joinToString("/") { if (classifier.isObfuscatedPackageSegment(it)) UNKNOWN else it }

    // -- anchors --------------------------------------------------------------------------------

    /** Names the obfuscator left alone end to end, e.g. `com/charlesproxy/gui/settings/HighlightPanel`. */
    private fun anchorStableNames() {
        for (node in old.nodes) {
            if (!isStable(node.name)) continue
            if (node.name !in new.byName) continue
            accept(node.name, node.name, "stable name")
        }
    }

    private fun anchorLoggerLiterals() {
        val targets = new.loggerLiterals.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        val sources = old.loggerLiterals.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size == 1 }

        for ((literal, owners) in sources) {
            val target = targets[literal] ?: continue
            accept(owners.single(), target, "logger literal")
        }
    }

    private fun isStable(internalName: String): Boolean {
        val pkg = internalName.substringBeforeLast('/', "")
        if (pkg.isNotEmpty() && pkg.split('/').any { classifier.isObfuscatedPackageSegment(it) }) return false
        return internalName.substringAfterLast('/').split('$').all { part ->
            part.isNotEmpty() && (part.all(Char::isDigit) || !classifier.isObfuscatedClassName(part))
        }
    }

    // -- the round ---------------------------------------------------------------------------------

    private fun classRound(): Int {
        packages = derivePackages()
        var gained = 0
        gained += matchUnique("name pattern", ::patternOld, ::patternNew)
        gained += matchUnique(
            "everything",
            { fullKey(it, old, isOldSide = true) },
            { fullKey(it, new, isOldSide = false) },
        )
        gained += matchUnique(
            "shape",
            { shape(it, ::tokenOld, strict = true) },
            { shape(it, ::tokenNew, strict = true) },
        )
        gained += matchUnique("strings", { stringKey(it, old) }, { stringKey(it, new) })
        gained += matchUnique(
            "code",
            { codeKey(it, old, isOldSide = true) },
            { codeKey(it, new, isOldSide = false) },
        )
        gained += matchUnique(
            "shape (partial)",
            { shape(it, ::tokenOld, strict = false) },
            { shape(it, ::tokenNew, strict = false) },
        )
        gained += matchUnique(
            "neighbourhood",
            { neighbourhood(it, old, ::tokenOld) },
            { neighbourhood(it, new, ::tokenNew) },
        )
        gained += matchUnique(
            "construction site",
            { creationKey(it, old, isOldSide = true) },
            { creationKey(it, new, isOldSide = false) },
        )
        gained += matchNested()
        return gained
    }

    /**
     * The workhorse: bucket both sides by a key and take the buckets holding exactly one class each.
     * Any pass expressible as "compute a key" gets the uniqueness rule for free this way.
     */
    private fun matchUnique(pass: String, keyOld: (ClassNode) -> String?, keyNew: (ClassNode) -> String?): Int {
        val oldByKey = HashMap<String, MutableList<String>>()
        for (node in old.nodes) {
            if (node.name in classOldToNew) continue
            oldByKey.getOrPut(keyOld(node) ?: continue) { mutableListOf() }.add(node.name)
        }
        if (oldByKey.isEmpty()) return 0

        val newByKey = HashMap<String, MutableList<String>>()
        for (node in new.nodes) {
            if (node.name in classNewToOld) continue
            val key = keyNew(node) ?: continue
            if (key !in oldByKey) continue
            newByKey.getOrPut(key) { mutableListOf() }.add(node.name)
        }

        var matched = 0
        for ((key, candidates) in oldByKey) {
            if (candidates.size != 1) continue
            val targets = newByKey[key] ?: continue
            if (targets.size != 1) continue
            if (accept(candidates.single(), targets.single(), pass)) matched++
        }
        return matched
    }

    // -- tokens ------------------------------------------------------------------------------------

    /**
     * Renders a type into a namespace both sides can compare in: a matched pair collapses to one
     * token, a type from outside the jar is already shared, and anything else is unknown.
     */
    private fun tokenOld(name: String): String =
        if (name in old.byName) classOldToNew[name]?.let { "@$it" } ?: UNKNOWN else name

    private fun tokenNew(name: String): String =
        if (name in new.byName) if (name in classNewToOld) "@$name" else UNKNOWN else name

    private fun descriptor(desc: String, token: (String) -> String): String =
        MatchSide.DESCRIPTOR_CLASS.replace(desc) { "L${token(it.groupValues[1])};" }

    private fun memberName(name: String): String =
        if (classifier.isObfuscatedMemberName(name)) "" else name

    /**
     * Renders a member reference into the shared namespace, the way [tokenOld] does for a type.
     *
     * A member of a class outside the jar keeps its own name whatever its length: the obfuscator could
     * not touch `java/util/List.add`, so treating a short external name as obfuscated only throws away
     * signal.
     */
    private fun refToken(ref: Ref, side: MatchSide, isOldSide: Boolean, isField: Boolean): String {
        val token = tokenFor(isOldSide)
        val desc = descriptor(ref.desc, token)
        if (ref.owner !in side.byName || !classifier.isObfuscatedMemberName(ref.name)) {
            return "${token(ref.owner)}.${ref.name}$desc"
        }

        val key = if (isField) Keys.field(ref.owner, ref.name, ref.desc)
        else Keys.method(ref.owner, ref.name, ref.desc)
        val matched = when {
            isOldSide && isField -> priorFields[key]
            isOldSide -> priorMethods[key]
            isField -> key.takeIf { it in priorFieldImages }
            else -> key.takeIf { it in priorMethodImages }
        }
        return matched?.let { "@$it" } ?: "${token(ref.owner)}.$UNKNOWN$desc"
    }

    // -- packages -----------------------------------------------------------------------------------

    /**
     * Where each old package ended up, voted for by the classes inside it.
     *
     * A strong majority rather than unanimity, because one class moving between packages should not
     * cost the whole package its identity — but the winner has to be the only old package claiming
     * it, so two packages merging leaves both unresolved instead of silently picking a side.
     */
    private fun derivePackages(): Map<String, String> {
        val votes = HashMap<String, MutableMap<String, Int>>()
        for ((oldName, newName) in classOldToNew) {
            val from = oldName.substringBeforeLast('/', "")
            val to = newName.substringBeforeLast('/', "")
            if (from.isEmpty() || to.isEmpty()) continue
            votes.getOrPut(from) { HashMap() }.merge(to, 1, Int::plus)
        }

        val chosen = HashMap<String, String>()
        for ((from, tally) in votes) {
            val total = tally.values.sum()
            val winner = tally.maxByOrNull { it.value } ?: continue
            if (winner.value * 5 >= total * 4) chosen[from] = winner.key
        }

        val claims = chosen.values.groupingBy { it }.eachCount()
        return chosen.filterValues { claims.getValue(it) == 1 }
    }

    private fun patternOld(node: ClassNode): String? {
        val pkg = node.name.substringBeforeLast('/', "")
        val rendered = when {
            pkg.isEmpty() -> ""
            else -> packages[pkg]
                ?: pkg.takeIf { path -> path.split('/').none(classifier::isObfuscatedPackageSegment) }
                ?: UNKNOWN
        }
        return pattern(rendered, node.name)
    }

    private fun patternNew(node: ClassNode): String? =
        pattern(node.name.substringBeforeLast('/', ""), node.name)

    private fun pattern(renderedPackage: String, internalName: String): String? {
        val simple = internalName.substringAfterLast('/').split('$').joinToString("$") { part ->
            when {
                part.all(Char::isDigit) -> part
                classifier.isObfuscatedClassName(part) -> UNKNOWN
                else -> part
            }
        }
        // Nothing readable anywhere in the name, so the pattern carries no information at all.
        if (renderedPackage == UNKNOWN && simple.all { it == '?' || it == '$' }) return null
        return "$renderedPackage/$simple"
    }

    // -- keys ---------------------------------------------------------------------------------------

    private fun shape(node: ClassNode, token: (String) -> String, strict: Boolean): String? {
        val out = StringBuilder()
        out.append(node.access and KIND_MASK).append('|')
        out.append(token(node.superName ?: "")).append('|')
        node.interfaces.orEmpty().map(token).sorted().joinTo(out, ",")
        out.append('|')
        node.fields.orEmpty()
            .map { "${it.access and MEMBER_MASK}${memberName(it.name)}${descriptor(it.desc, token)}" }
            .sorted().joinTo(out, ",")
        out.append('|')
        node.methods.orEmpty()
            .map { "${it.access and MEMBER_MASK}${memberName(it.name)}${descriptor(it.desc, token)}" }
            .sorted().joinTo(out, ",")

        if (strict && out.contains(UNKNOWN)) return null
        return out.toString()
    }

    /**
     * What the class' methods compile to, which is the sharpest signal there is short of the strings:
     * two classes with the same descriptors and the same neighbourhood are still telling each other
     * apart by their opcode sequences, and a rename cannot touch those.
     *
     * Constructors are deliberately included even though they never get an intermediary ID — a class'
     * `<init>` is often the only thing that distinguishes it from its shape-alikes.
     */
    private fun codeKey(node: ClassNode, side: MatchSide, isOldSide: Boolean): String? {
        val token = tokenFor(isOldSide)
        val parts = node.methods.orEmpty().mapNotNull { method ->
            val body = body(node.name, method, side, isOldSide) ?: return@mapNotNull null
            "${method.access and MEMBER_MASK}${memberName(method.name)}${descriptor(method.desc, token)}#$body"
        }.sorted()
        if (parts.isEmpty()) return null
        return (node.access and KIND_MASK).toString() + "|" + token(node.superName ?: "") + "|" +
            parts.joinToString(",")
    }

    /**
     * Every signal at once, which is the pass that does the most work.
     *
     * Combining keys can only help: a longer key means smaller buckets, which means *more* singletons
     * and so more matches, each backed by strictly more evidence than any one signal alone. The
     * coarser passes still earn their place afterwards, for the classes that changed enough that
     * their combined key no longer agrees across versions.
     */
    private fun fullKey(node: ClassNode, side: MatchSide, isOldSide: Boolean): String? {
        val token = tokenFor(isOldSide)
        val shape = shape(node, token, strict = false) ?: return null
        val code = codeKey(node, side, isOldSide).orEmpty()
        val strings = side.strings[node.name].orEmpty().joinToString(" ")
        val out = side.outgoing[node.name].orEmpty().map(token).filter { it != UNKNOWN }.sorted()
        val into = side.incoming[node.name].orEmpty().map(token).filter { it != UNKNOWN }.sorted()
        return "$shape#$code#$strings#${out.joinToString(",")}#${into.joinToString(",")}"
    }

    /**
     * The class' string constants. Thin sets are refused rather than trusted: one shared `"UTF-8"`
     * happening to be unique on both sides is a coincidence, a paragraph of UI text is not.
     */
    private fun stringKey(node: ClassNode, side: MatchSide): String? {
        val strings = side.strings[node.name].orEmpty()
        if (strings.size < 3 && strings.sumOf { it.length } < 16) return null
        return (node.access and KIND_MASK).toString() + "|" + strings.joinToString(" ")
    }

    /** Which matched classes this one points at, and which matched classes point at it. */
    private fun neighbourhood(node: ClassNode, side: MatchSide, token: (String) -> String): String? {
        val out = side.outgoing[node.name].orEmpty().map(token).filter { it != UNKNOWN }.sorted()
        val into = side.incoming[node.name].orEmpty().map(token).filter { it != UNKNOWN }.sorted()
        if (out.size + into.size < 3) return null
        return (node.access and KIND_MASK).toString() + "|" + out.joinToString(",") + "|" + into.joinToString(",")
    }

    /**
     * Where the class is constructed, which is the last thing that separates two classes the jar
     * describes identically — the flattened `ActionListener`s and `Comparator`s the obfuscator lifted
     * out of their outer class, which have the same supertype, the same fields and the same bodies as
     * their siblings.
     *
     * The position of the `new` among the `new`s of the method doing it is what makes this decisive
     * rather than a guess: the two siblings are constructed at different points of the same already-
     * matched method, and an edit to that method changes the key on both sides rather than one.
     */
    private fun creationKey(node: ClassNode, side: MatchSide, isOldSide: Boolean): String? {
        val sites = side.creations[node.name].orEmpty().mapNotNull { creation ->
            val site = creation.site
            val token = if (site.name == "<init>" || site.name == "<clinit>") {
                // Constructors are never renamed and never get an ID, so they are placed by their owner.
                val owner = if (isOldSide) classOldToNew[site.owner] else site.owner.takeIf { it in classNewToOld }
                owner?.let { "$it.${site.name}${descriptor(site.desc, tokenFor(isOldSide))}" }
            } else {
                val key = Keys.method(site.owner, site.name, site.desc)
                if (isOldSide) methodOldToNew[key] else key.takeIf { it in matchedMethods }
            }
            token?.let { "@$it#${creation.ordinal}" }
        }.sorted()
        return if (sites.isEmpty()) null else sites.joinToString(",")
    }

    private fun tokenFor(isOldSide: Boolean): (String) -> String =
        if (isOldSide) ::tokenOld else ::tokenNew

    /**
     * Inner classes of a matched outer. The obfuscator keeps positional inners (`Foo$1`) positional,
     * so an identical tail is decisive; failing that, one candidate left on each side is too.
     */
    private fun matchNested(): Int {
        var matched = 0
        val pending = old.nodes.map { it.name }
            .filter { it !in classOldToNew && '$' in it.substringAfterLast('/') }
            .groupBy { it.substring(0, it.lastIndexOf('$')) }

        for ((outer, candidates) in pending) {
            val newOuter = classOldToNew[outer] ?: continue
            val targets = new.inners[newOuter].orEmpty().filter { it !in classNewToOld }.toMutableList()

            val unresolved = ArrayList<String>()
            for (name in candidates) {
                val tail = name.substringAfterLast('$')
                val exact = targets.singleOrNull { it.substringAfterLast('$') == tail }
                if (exact != null && accept(name, exact, "nested")) {
                    targets.remove(exact)
                    matched++
                } else {
                    unresolved.add(name)
                }
            }

            if (unresolved.size == 1 && targets.size == 1 && accept(unresolved.single(), targets.single(), "nested")) {
                matched++
            }
        }
        return matched
    }

    // -- members ------------------------------------------------------------------------------------

    private fun matchMembers() {
        priorFields = HashMap(fieldOldToNew)
        priorMethods = HashMap(methodOldToNew)
        priorFieldImages = priorFields.values.toHashSet()
        priorMethodImages = priorMethods.values.toHashSet()

        fieldOldToNew.clear()
        methodOldToNew.clear()
        matchedDescriptors.clear()
        memberPasses.clear()
        provenance.clear()
        for ((oldName, newName) in classOldToNew) {
            val from = old.byName.getValue(oldName)
            val to = new.byName.getValue(newName)
            matchFields(from, to)
            matchMethods(from, to)
        }
    }

    private fun linkField(owner: ClassNode, field: FieldNode, target: ClassNode, with: FieldNode, pass: String) {
        val key = Keys.field(owner.name, field.name, field.desc)
        fieldOldToNew[key] = Keys.field(target.name, with.name, with.desc)
        provenance[key] = pass
        matchedDescriptors.add(field.desc to with.desc)
        memberPasses.merge(pass, 1, Int::plus)
    }

    private fun linkMethod(owner: ClassNode, method: MethodNode, target: ClassNode, with: MethodNode, pass: String) {
        val key = Keys.method(owner.name, method.name, method.desc)
        methodOldToNew[key] = Keys.method(target.name, with.name, with.desc)
        provenance[key] = pass
        matchedDescriptors.add(method.desc to with.desc)
        memberPasses.merge(pass, 1, Int::plus)
    }

    private fun matchFields(from: ClassNode, to: ClassNode) {
        val targetsByName = to.fields.orEmpty().associateBy { it.name }
        val claimed = HashSet<String>()
        val pendingOld = ArrayList<FieldNode>()

        // A readable field name survived the obfuscator, and a name is unique within a class.
        for (field in from.fields.orEmpty()) {
            if (classifier.isObfuscatedMemberName(field.name)) {
                pendingOld.add(field)
                continue
            }
            val target = targetsByName[field.name] ?: continue
            if (claimed.add(target.name)) linkField(from, field, to, target, "field: readable name")
        }

        val pendingNew = to.fields.orEmpty().filter { classifier.isObfuscatedMemberName(it.name) }
        val oldBuckets = pendingOld.groupBy { "${it.access and MEMBER_MASK}${descriptor(it.desc, ::tokenOld)}" }
        val newBuckets = pendingNew.groupBy { "${it.access and MEMBER_MASK}${descriptor(it.desc, ::tokenNew)}" }

        for ((bucket, fields) in oldBuckets) {
            val targets = newBuckets[bucket] ?: continue
            if (fields.size == 1 && targets.size == 1) {
                linkField(from, fields.single(), to, targets.single(), "field: descriptor")
                continue
            }

            // Same type, same modifiers, same class: separate them by where they are used from.
            val remainingOld = ArrayList(fields)
            val remainingNew = ArrayList(targets)
            val usesOld = fields.associateWith { usageKey(from, it, old, isOldSide = true) }
            val usesNew = targets.groupBy { usageKey(to, it, new, isOldSide = false) }
            for (field in fields) {
                val key = usesOld[field] ?: continue
                if (usesOld.values.count { it == key } != 1) continue
                val candidates = usesNew[key] ?: continue
                val target = candidates.singleOrNull() ?: continue
                if (target !in remainingNew) continue
                linkField(from, field, to, target, "field: usage")
                remainingOld.remove(field)
                remainingNew.remove(target)
            }

            if (orderTrustworthy(fields.size, targets.size, remainingOld.size, remainingNew.size)) {
                for ((field, target) in remainingOld.zip(remainingNew)) {
                    linkField(from, field, to, target, "field: declaration order")
                }
            }
        }
    }

    private fun usageKey(owner: ClassNode, field: FieldNode, side: MatchSide, isOldSide: Boolean): String? {
        val uses = side.fieldUses[Keys.field(owner.name, field.name, field.desc)].orEmpty()
        if (uses.isEmpty()) return null
        return uses.map { refToken(it, side, isOldSide, isField = false) }.sorted().joinToString(",")
    }

    private fun matchMethods(from: ClassNode, to: ClassNode) {
        val targets = to.methods.orEmpty().filter { it.name != "<init>" && it.name != "<clinit>" }
        val claimed = HashSet<MethodNode>()
        val pendingOld = ArrayList<MethodNode>()

        // Readable name plus descriptor, so overloads stay apart.
        val targetsBySignature = targets.associateBy { "${it.name}${descriptor(it.desc, ::tokenNew)}" }
        for (method in from.methods.orEmpty()) {
            if (method.name == "<init>" || method.name == "<clinit>") continue
            if (classifier.isObfuscatedMemberName(method.name)) {
                pendingOld.add(method)
                continue
            }
            val target = targetsBySignature["${method.name}${descriptor(method.desc, ::tokenOld)}"] ?: continue
            if (claimed.add(target)) linkMethod(from, method, to, target, "method: readable name")
        }

        val pendingNew = targets.filter { classifier.isObfuscatedMemberName(it.name) && it !in claimed }
        val oldBuckets = pendingOld.groupBy { "${it.access and MEMBER_MASK}${descriptor(it.desc, ::tokenOld)}" }
        val newBuckets = pendingNew.groupBy { "${it.access and MEMBER_MASK}${descriptor(it.desc, ::tokenNew)}" }

        for ((bucket, methods) in oldBuckets) {
            val candidates = newBuckets[bucket] ?: continue
            if (methods.size == 1 && candidates.size == 1) {
                linkMethod(from, methods.single(), to, candidates.single(), "method: descriptor")
                continue
            }

            // Overloads sharing a descriptor are separated by what they actually do.
            val remainingOld = ArrayList(methods)
            val remainingNew = ArrayList(candidates)
            // A bucket that changed size had something added to it or taken out of it, and the one
            // that was taken out can key identically to a survivor once tokenisation has blurred an
            // unmatched reference on one side. A thin body -- a getter, a one-line setter -- is where
            // that coincidence lives, so an unstable bucket only accepts a body with content in it.
            val stable = methods.size == candidates.size
            val bodiesOld = methods.associateWith { body(from, it, old, isOldSide = true) }
            val bodiesNew = candidates.groupBy { body(to, it, new, isOldSide = false) }
            for (method in methods) {
                val key = bodiesOld[method] ?: continue
                if (bodiesOld.values.count { it == key } != 1) continue
                if (!stable && !substantial(from.name, method, old, isOldSide = true)) continue
                val matches = bodiesNew[key] ?: continue
                val target = matches.singleOrNull() ?: continue
                if (target !in remainingNew) continue
                linkMethod(from, method, to, target, "method: body")
                remainingOld.remove(method)
                remainingNew.remove(target)
            }

            if (orderTrustworthy(methods.size, candidates.size, remainingOld.size, remainingNew.size)) {
                for ((method, target) in remainingOld.zip(remainingNew)) {
                    linkMethod(from, method, to, target, "method: declaration order")
                }
            }
        }
    }

    /**
     * Whether the body names anything the matcher has actually pinned down.
     *
     * Size is the wrong measure — what makes two accessors indistinguishable is not that they are
     * short but that everything they touch is still unknown, so both render as `?` and coincide. One
     * string constant, one call into a matched method, one read of a matched field, or anything
     * outside the jar (which the obfuscator never renamed) is enough to be talking about a specific
     * method rather than a shape.
     */
    private fun substantial(owner: String, method: MethodNode, side: MatchSide, isOldSide: Boolean): Boolean {
        val code = side.code[Keys.method(owner, method.name, method.desc)] ?: return false
        if (code.strings.isNotEmpty()) return true
        return code.calls.any { UNKNOWN !in refToken(it, side, isOldSide, isField = false) } ||
            code.fields.any { UNKNOWN !in refToken(it, side, isOldSide, isField = true) }
    }

    /**
     * Whether declaration order can be trusted for what is left of a descriptor bucket.
     *
     * The leftovers matching in count is not enough on its own — one member added and one removed
     * leaves the count intact and every pairing after the edit off by one. The *whole* bucket having
     * the same size on both sides is the real condition: nothing was added to it or taken from it, so
     * the order that remains is the order that was.
     */
    private fun orderTrustworthy(bucketOld: Int, bucketNew: Int, leftOld: Int, leftNew: Int): Boolean =
        positional && leftOld > 0 && leftOld == leftNew && bucketOld == bucketNew

    private fun body(owner: ClassNode, method: MethodNode, side: MatchSide, isOldSide: Boolean): String? =
        body(owner.name, method, side, isOldSide)

    private fun body(owner: String, method: MethodNode, side: MatchSide, isOldSide: Boolean): String? {
        val code = side.code[Keys.method(owner, method.name, method.desc)] ?: return null
        return buildString {
            append(code.shape).append('|')
            code.strings.joinTo(this, " ")
            append('|')
            code.calls.joinTo(this, ",") { refToken(it, side, isOldSide, isField = false) }
            append('|')
            code.fields.joinTo(this, ",") { refToken(it, side, isOldSide, isField = true) }
        }
    }

    // -- propagation ----------------------------------------------------------------------------------

    /**
     * Reads new class matches out of the ones already made: if two classes are the same class, their
     * supertypes are the same supertype, and the types in their matched members' descriptors line up
     * position by position.
     *
     * Every pairing this produces is a *vote*, and only a vote that is the sole candidate in both
     * directions is accepted — so two types swapping places, or one splitting in two, leaves them
     * unmatched rather than picking a side.
     */
    private fun propagateTypes(): Int {
        val forward = HashMap<String, MutableSet<String>>()
        val backward = HashMap<String, MutableSet<String>>()

        fun vote(from: String?, to: String?) {
            if (from == null || to == null) return
            if (from !in old.byName || to !in new.byName) return
            if (from in classOldToNew || to in classNewToOld) return
            forward.getOrPut(from) { HashSet() }.add(to)
            backward.getOrPut(to) { HashSet() }.add(from)
        }

        for ((oldName, newName) in classOldToNew) {
            val from = old.byName.getValue(oldName)
            val to = new.byName.getValue(newName)
            vote(from.superName, to.superName)
            val fromInterfaces = from.interfaces.orEmpty().filter { it in old.byName && it !in classOldToNew }
            val toInterfaces = to.interfaces.orEmpty().filter { it in new.byName && it !in classNewToOld }
            if (fromInterfaces.size == 1 && toInterfaces.size == 1) {
                vote(fromInterfaces.single(), toInterfaces.single())
            }
        }

        for ((fromDesc, toDesc) in matchedDescriptors) alignTypes(fromDesc, toDesc, ::vote)

        var matched = 0
        for ((from, candidates) in forward) {
            val to = candidates.singleOrNull() ?: continue
            if (backward.getValue(to).size != 1) continue
            if (accept(from, to, "type propagation")) matched++
        }
        return matched
    }

    private fun alignTypes(fromDesc: String, toDesc: String, vote: (String?, String?) -> Unit) {
        // Only types of the same shape are the same type. A field that kept its readable name while
        // changing from `Foo` to `Bar[]` is a real thing, and voting its old type onto its new one
        // would be reading a rename into an edit.
        fun element(type: Type): String? {
            var current = type
            while (current.sort == Type.ARRAY) current = current.elementType
            return if (current.sort == Type.OBJECT) current.internalName else null
        }

        fun aligned(a: Type, b: Type): Boolean = a.sort == b.sort &&
            (a.sort != Type.ARRAY || a.dimensions == b.dimensions)

        fun pair(a: Type, b: Type) {
            if (aligned(a, b)) vote(element(a), element(b))
        }

        if (fromDesc.startsWith("(") != toDesc.startsWith("(")) return
        if (!fromDesc.startsWith("(")) {
            pair(Type.getType(fromDesc), Type.getType(toDesc))
            return
        }

        val from = Type.getMethodType(fromDesc)
        val to = Type.getMethodType(toDesc)
        if (from.argumentTypes.size != to.argumentTypes.size) return
        for (index in from.argumentTypes.indices) {
            pair(from.argumentTypes[index], to.argumentTypes[index])
        }
        pair(from.returnType, to.returnType)
    }

    private companion object {
        const val UNKNOWN = "?"
        const val MAX_ROUNDS = 40

        /** What a class fundamentally is. A patch release does not change it. */
        const val KIND_MASK = Opcodes.ACC_INTERFACE or Opcodes.ACC_ENUM or
            Opcodes.ACC_ANNOTATION or Opcodes.ACC_RECORD

        /** Member modifiers stable enough to bucket on. */
        const val MEMBER_MASK = Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ABSTRACT
    }
}
