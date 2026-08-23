package alaphant.build

import org.gradle.api.logging.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File

/** The bootstrap passes. [BootstrapNamesTask] documents what each one is and how far to trust it. */
internal class Recovery(
    private val jar: Jar,
    private val intermediary: IntermediaryIndex,
    private val store: NamedStore,
    private val logger: Logger,
) {
    /** Classes whose intermediary name is still a `class_N` placeholder, i.e. the ones needing names. */
    private val placeholders: Map<String, String> = intermediary.classMap
        .filter { (_, name) -> name.substringAfterLast('/').startsWith("class_") }

    // -- 1. Lombok @Slf4j logger literals ---------------------------------------------------------

    fun loggerLiterals() {
        var named = 0
        var skipped = 0

        for (node in jar.nodes) {
            val intermediaryName = placeholders[node.name] ?: continue
            val literal = loggerLiteral(node) ?: continue
            val internal = literal.replace('.', '/')

            if (internal in jar.byName) continue
            if (!plausibleRename(node.name, internal)) {
                skipped++
                continue
            }

            if (store.name(intermediaryName, internal, "named by its own Lombok @Slf4j logger literal \"$literal\"")) named++
        }

        logger.lifecycle("  @Slf4j logger literals    : $named classes named" + skippedNote(skipped))
    }

    /** The string handed to `LoggerFactory.getLogger(String)`, if this class does that exactly once. */
    private fun loggerLiteral(node: ClassNode): String? {
        val found = mutableSetOf<String>()
        for (method in node.methods.orEmpty()) {
            val instructions = method.instructions ?: continue
            for (insn in instructions) {
                if (insn !is MethodInsnNode) continue
                if (insn.owner != SLF4J_FACTORY || insn.name != "getLogger" || insn.desc != GET_LOGGER_STRING) continue
                val previous = insn.previous as? LdcInsnNode ?: continue
                (previous.cst as? String)?.let { found.add(it) }
            }
        }
        return found.singleOrNull()
    }

    // -- 2. Inner-class file-name leaks -----------------------------------------------------------

    fun innerClassLeaks() {
        // enclosing original name -> the obfuscated classes an inner points at
        val claims = HashMap<String, MutableSet<String>>()
        var deferred = 0

        for (node in jar.nodes) {
            val lastNesting = node.name.lastIndexOf('$')
            if (lastNesting < 0) continue

            val enclosing = node.name.substring(0, lastNesting)
            if (enclosing in jar.byName) continue

            val hint = enclosingHint(node) ?: continue
            if (hint !in placeholders) continue
            if (hint.substringBeforeLast('/') != enclosing.substringBeforeLast('/')) continue

            if ('$' in enclosing.substringAfterLast('/')) {
                // The leaked name is itself nested and its own outer is missing too, so a name here
                // would need a second inference. Record the evidence instead.
                store.comment(
                    placeholders.getValue(hint),
                    "an inner class the obfuscator left alone, ${node.name}, is enclosed by this class, " +
                        "so this was originally $enclosing",
                )
                deferred++
                continue
            }

            claims.getOrPut(enclosing) { mutableSetOf() }.add(hint)
        }

        // Ambiguous either way round is not evidence; drop both sides rather than pick.
        val byHint = HashMap<String, MutableSet<String>>()
        for ((enclosing, hints) in claims) {
            hints.singleOrNull()?.let { byHint.getOrPut(it) { mutableSetOf() }.add(enclosing) }
        }

        var named = 0
        var conflicts = claims.count { it.value.size > 1 }
        for ((hint, enclosingNames) in byHint) {
            val enclosing = enclosingNames.singleOrNull()
            if (enclosing == null) {
                conflicts++
                continue
            }
            if (store.name(
                    placeholders.getValue(hint),
                    enclosing,
                    "named by an inner class the obfuscator left alone: ${enclosing.substringAfterLast('/')}\$… " +
                        "is in the jar while its outer class is not, and holds a synthetic reference to this type",
                )
            ) {
                named++
            }
        }

        logger.lifecycle(
            "  inner-class name leaks    : $named classes named" +
                (if (deferred > 0) ", $deferred commented" else "") +
                (if (conflicts > 0) ", $conflicts ambiguous" else "")
        )
    }

    /**
     * The enclosing class an inner class points at: its synthetic `this$N` field, or failing that the
     * first constructor parameter.
     */
    private fun enclosingHint(node: ClassNode): String? {
        val candidates = LinkedHashSet<String>()

        for (field in node.fields.orEmpty()) {
            if (!field.name.startsWith("this$")) continue
            field.desc.internalName()?.let { candidates.add(it) }
        }
        if (candidates.isEmpty()) {
            for (method in node.methods.orEmpty()) {
                if (method.name != "<init>" || !method.desc.startsWith("(L")) continue
                method.desc.substring(1, method.desc.indexOf(';') + 1).internalName()?.let { candidates.add(it) }
                break
            }
        }

        return candidates.filter { it in jar.byName }.singleOrNull()
    }

    // -- 3. module-info services ------------------------------------------------------------------

    fun moduleServices() {
        val bytes = jar.moduleInfo ?: run {
            logger.lifecycle("  module-info services      : no module descriptor")
            return
        }

        val provides = LinkedHashMap<String, List<String>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitModule(name: String, access: Int, version: String?): ModuleVisitor =
                    object : ModuleVisitor(Opcodes.ASM9) {
                        override fun visitProvide(service: String, vararg providers: String) {
                            provides[service] = providers.toList()
                        }
                    }
            },
            0,
        )

        var proposed = 0
        var commented = 0

        for ((service, providers) in provides) {
            val readableProviders = providers.filter { it !in placeholders }.map { it.substringAfterLast('/') }

            placeholders[service]?.let { serviceIntermediary ->
                val description = "service interface declared in module-info, implemented by " +
                    providers.joinToString(", ") { named(it) }
                val suffix = commonCamelSuffix(readableProviders)
                if (suffix != null && readableProviders.size == providers.size) {
                    if (store.name(serviceIntermediary, "${service.substringBeforeLast('/')}/Maybe$suffix", "$description; every provider ends in \"$suffix\"")) {
                        proposed++
                    }
                } else {
                    store.comment(serviceIntermediary, description)
                    commented++
                }
            }

            for (provider in providers) {
                val providerIntermediary = placeholders[provider] ?: continue
                store.comment(providerIntermediary, "declared in module-info as a provider of ${named(service)}")
                commented++
            }
        }

        logger.lifecycle("  module-info services      : $proposed provisional names, $commented classes commented (${provides.size} services)")
    }

    /** The longest shared trailing run of CamelCase words, e.g. `…Serialization` across providers. */
    private fun commonCamelSuffix(names: List<String>): String? {
        if (names.size < 2) return null
        val wordLists = names.map { it.splitCamelCase() }
        val shortest = wordLists.minOf { it.size }
        var shared = 0
        while (shared < shortest) {
            val word = wordLists[0][wordLists[0].size - 1 - shared]
            if (wordLists.any { it[it.size - 1 - shared] != word }) break
            shared++
        }
        if (shared == 0) return null
        // A suffix that is the whole of some provider's name says nothing new.
        if (wordLists.any { it.size == shared }) return null
        return wordLists[0].takeLast(shared).joinToString("")
    }

    // -- 4. the Charles 4 corpus ------------------------------------------------------------------

    fun charles4Corpus(legacy: File) {
        val corpus = Charles4Corpus.read(legacy)
        val translator = DescriptorTranslator(corpus, jar, placeholders.keys)

        // Candidate names per package: Charles 4 names that no Charles 5 class already carries.
        val candidatesByPackage = HashMap<String, MutableList<Charles4Corpus.Entry>>()
        for (entry in corpus.entries) {
            // An entry with neither a class name nor a named member has nothing to give.
            if (!entry.hasName && entry.namedMembers == 0) continue
            val name5 = charles5Name(entry.named)
            if (entry.hasName && name5 in jar.byName) continue
            candidatesByPackage.getOrPut(name5.substringBeforeLast('/', "")) { mutableListOf() }.add(entry)
        }

        val targetsByPackage = placeholders.keys
            .filter { '$' !in it }
            .groupBy { it.substringBeforeLast('/', "") }

        var namedClasses = 0
        var namedMembers = 0
        var considered = 0

        for ((pkg, targets) in targetsByPackage) {
            val candidates = candidatesByPackage[pkg] ?: continue
            considered += minOf(targets.size, candidates.size)

            val scores = HashMap<String, MutableList<Pair<Charles4Corpus.Entry, Int>>>()
            val reverse = HashMap<Charles4Corpus.Entry, MutableList<Pair<String, Int>>>()

            for (target in targets) {
                val node = jar.byName[target] ?: continue
                val shape = translator.shapeOf(node)
                for (candidate in candidates) {
                    val score = translator.score(candidate, shape)
                    if (score < MIN_SHAPE_MATCHES) continue
                    scores.getOrPut(target) { mutableListOf() }.add(candidate to score)
                    reverse.getOrPut(candidate) { mutableListOf() }.add(target to score)
                }
            }

            for ((target, ranked) in scores) {
                val best = ranked.maxByOrNull { it.second } ?: continue
                val runnerUp = ranked.filter { it !== best }.maxOfOrNull { it.second } ?: 0
                if (best.second < runnerUp + MIN_MARGIN) continue

                // Require the match to be mutual: the Charles 4 class must prefer this target too.
                val backwards = reverse[best.first]?.maxByOrNull { it.second } ?: continue
                if (backwards.first != target) continue

                val simple = flatten(charles5Name(best.first.named).substringAfterLast('/'))
                val evidence = "matched to Charles 4's $simple on ${best.second} shared member descriptors " +
                    "(next best in this package scored $runnerUp) -- provisional, confirm before dropping the Maybe"

                if (best.first.hasName) {
                    if (store.name(intermediary.classOf(target) ?: continue, "$pkg/Maybe$simple", evidence)) {
                        namedClasses++
                    }
                } else {
                    // Charles 4 never named this class either, but it did name some of its members.
                    store.comment(
                        intermediary.classOf(target) ?: continue,
                        "matched to Charles 4's $simple on ${best.second} shared member descriptors; " +
                            "Charles 4 had no name for the class itself, only for some of its members",
                    )
                }
                namedMembers += transferMembers(target, best.first, translator)
            }
        }

        logger.lifecycle(
            "  Charles 4 corpus          : $namedClasses provisional class names, $namedMembers member names " +
                "(from $considered possible pairings in ${targetsByPackage.keys.count { candidatesByPackage.containsKey(it) }} packages)"
        )
    }

    /** Carries member names across a matched pair, where the descriptor pins the member on both sides. */
    private fun transferMembers(
        target: String,
        candidate: Charles4Corpus.Entry,
        translator: DescriptorTranslator,
    ): Int {
        val node = jar.byName[target] ?: return 0
        var transferred = 0

        for ((kind, members) in listOf(
            MemberKind.FIELD to candidate.fields,
            MemberKind.METHOD to candidate.methods,
        )) {
            val byShape = members.filter { isRealCharles4MemberName(it) }.groupBy { translator.translate(it.desc) }
            val targets = when (kind) {
                MemberKind.FIELD -> node.fields.orEmpty().map { it.name to it.desc }
                MemberKind.METHOD -> node.methods.orEmpty()
                    .filter { it.name != "<init>" && it.name != "<clinit>" }
                    .map { it.name to it.desc }
            }
            val targetsByShape = targets.groupBy { translator.shape(it.second) }

            for ((shape, candidateMembers) in byShape) {
                val candidateMember = candidateMembers.singleOrNull() ?: continue
                val targetMember = targetsByShape[shape]?.singleOrNull() ?: continue
                if (!isObfuscatedMember(targetMember.first)) continue

                // Keyed on the intermediary name and descriptor, which is what the store speaks.
                val mapped = when (kind) {
                    MemberKind.FIELD -> intermediary.field(target, targetMember.first, targetMember.second)
                    MemberKind.METHOD -> intermediary.method(target, targetMember.first, targetMember.second)
                } ?: continue
                val key = NamedStore.MemberKey(mapped.name, mapped.desc)
                val owner = intermediary.classOf(target) ?: continue
                val evidence = "carried over from Charles 4's ${candidate.named.substringAfterLast('/')}." +
                    "${candidateMember.named} on a unique descriptor match -- provisional"
                val provisional = "maybe" + candidateMember.named.replaceFirstChar { it.uppercaseChar() }
                val landed = when (kind) {
                    MemberKind.FIELD -> store.nameField(owner, key, provisional, evidence)
                    MemberKind.METHOD -> store.nameMethod(owner, key, provisional, evidence)
                }
                if (landed) transferred++
            }
        }

        return transferred
    }

    private enum class MemberKind { FIELD, METHOD }

    /**
     * Enigma reads `$` as nesting, so a flat class given a nested name comes back truncated and
     * collides with its siblings. Run the words together instead.
     */
    private fun flatten(simple: String): String = simple.replace("$", "")

    /** Charles 4's own placeholders (`field_551`, `method_563`) look like names and carry none. */
    private fun isRealCharles4MemberName(member: Charles4Corpus.Member): Boolean =
        member.named != member.official &&
            !CHARLES4_PLACEHOLDER.matches(member.named) &&
            member.named.first().isLetter()

    /** Cheap re-check that a member name is obfuscator output, so readable names are left alone. */
    private fun isObfuscatedMember(name: String): Boolean =
        name.length <= 4 && name.all { it.isLetter() || it == '_' }

    // -- shared helpers ---------------------------------------------------------------------------

    private fun named(internalName: String): String =
        store.existing(intermediary.classOf(internalName) ?: internalName)?.name?.substringAfterLast('/')
            ?: internalName.substringAfterLast('/')

    private fun skippedNote(count: Int) = if (count > 0) " ($count rejected as implausible)" else ""

    /**
     * The readable package segments have to agree; the obfuscated tail may differ, since a leaked
     * name carries the original package too (`macos/MkAr` was `macos/gui`).
     */
    private fun plausibleRename(official: String, leaked: String): Boolean {
        val readablePrefix = official.substringBeforeLast('/', "")
            .split('/')
            .takeWhile { !(it.length == 4 && it.all(Char::isLetter) && it.any(Char::isUpperCase)) }
            .joinToString("/")
        return leaked.startsWith("$readablePrefix/")
    }

    private fun String.internalName(): String? =
        takeIf { it.startsWith("L") && it.endsWith(";") }?.substring(1, length - 1)

    private fun String.splitCamelCase(): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in this) {
            if (ch.isUpperCase() && current.isNotEmpty()) {
                words.add(current.toString())
                current.clear()
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }

    companion object {
        const val SLF4J_FACTORY = "org/slf4j/LoggerFactory"
        const val GET_LOGGER_STRING = "(Ljava/lang/String;)Lorg/slf4j/Logger;"

        /** Charles 4 lived under `com/xk72/charles`; Charles 5 moved that tree to `com/charlesproxy`. */
        fun charles5Name(charles4: String): String =
            if (charles4.startsWith(CHARLES4_ROOT)) "com/charlesproxy/" + charles4.removePrefix(CHARLES4_ROOT)
            else charles4

        private const val CHARLES4_ROOT = "com/xk72/charles/"

        /** How many member descriptors must line up before a Charles 4 pairing is worth proposing. */
        const val MIN_SHAPE_MATCHES = 4

        /** …and by how much it must beat the next candidate in the same package. */
        const val MIN_MARGIN = 2

        val CHARLES4_PLACEHOLDER = Regex("""(field|method|class)_\d+""")
    }
}
