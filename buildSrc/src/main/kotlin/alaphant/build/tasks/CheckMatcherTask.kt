package alaphant.build.tasks

import alaphant.build.mappings.Keys
import alaphant.build.mappings.Ledger
import alaphant.build.mappings.MatchSide
import alaphant.build.mappings.MatchTransfer
import alaphant.build.mappings.MethodGroups
import alaphant.build.mappings.NameClassifier
import alaphant.build.mappings.VersionMatcher
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import java.io.File

/**
 * Checks [VersionMatcher] against a jar whose correct answer is known.
 *
 * There is no second Charles release to test against until there is one, and by then the mappings
 * are already riding on the result — so the fixture is built instead of found: take the installed
 * jar, put every obfuscated name through the obfuscator again, and match the original against the
 * result. Every element's counterpart is known by construction, so the match can be scored exactly
 * rather than eyeballed.
 *
 * **A wrong match fails the build; a missing one does not.** That asymmetry is the whole design of
 * the matcher restated as a test. A missing match costs a fresh ID and a name that has to be worked
 * out again; a wrong one silently moves a hand-written name onto the wrong element, and nothing
 * downstream will catch it. Recall is reported so the trend is visible, but only precision is a gate.
 *
 * What this does and does not prove:
 *
 *  - It **does** prove the passes are genuinely name-independent, that override-group IDs land on the
 *    right group when the representative changes name, and that the transfer writes what it should.
 *  - It **does not** prove robustness against a real release, where bodies change and members come
 *    and go. `--drop-classes` and `--drop-methods` push in that direction by deleting a share of the
 *    jar — enough to perturb shapes, neighbourhoods and call graphs — but a rewritten method body is
 *    something only a real upgrade produces.
 *
 * The scrambled jar is a matching fixture, not a runnable one: `invokedynamic` call-site names are
 * left alone, since matching erases obfuscated member names anyway.
 */
abstract class CheckMatcherTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val officialJar: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readableNames: RegularFileProperty

    /** Only read, and only to check the transfer lands IDs where they belong. */
    @get:Internal
    abstract val ledgerFile: RegularFileProperty

    @get:Input
    abstract val charlesVersion: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "drop-classes", description = "Percentage of obfuscated classes to delete from the fixture.")
    abstract val dropClasses: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "drop-methods", description = "Percentage of obfuscated methods to delete from the fixture.")
    abstract val dropMethods: Property<String>

    @get:OutputFile
    abstract val scrambledJar: RegularFileProperty

    /** Every element that failed to match, so a drop in recall is diagnosable. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val classifier = readableNames.orNull
            ?.let { NameClassifier.read(it.asFile) }
            ?: NameClassifier(emptySet(), emptySet())

        val classDrop = percentage(dropClasses, "drop-classes")
        val methodDrop = percentage(dropMethods, "drop-methods")

        val original = Jar.read(officialJar.get().asFile)
        val fixture = Scramble(Jar.read(officialJar.get().asFile), classifier, classDrop, methodDrop)
        val target = scrambledJar.get().asFile
        fixture.writeTo(target)

        logger.lifecycle("Matcher self-check on Charles ${charlesVersion.get()}")
        logger.lifecycle("  fixture: ${fixture.kept} of ${original.nodes.size} classes, " +
            "$classDrop% classes and $methodDrop% methods dropped")

        val old = MatchSide(original, classifier)
        val new = MatchSide(Jar.read(target), classifier)
        val result = VersionMatcher(old, new, positional = true, log = { logger.info(it) }).match()

        val score = Score(result.provenance)
        scoreClasses(score, old, fixture, result.classes)
        scoreMembers(score, old, fixture, new, result)
        scoreTransfer(score, old, new, fixture, result)

        logger.lifecycle("")
        score.report(logger::lifecycle)

        val detail = report.get().asFile
        detail.parentFile?.mkdirs()
        detail.writeText(score.detail(result.unmatched))
        logger.lifecycle("  misses listed in $detail")

        if (score.wrong > 0) {
            throw GradleException(
                "${score.wrong} wrong match(es). A wrong match moves a hand-written name onto the " +
                    "wrong element, which nothing downstream detects. See the listing above."
            )
        }
    }

    private fun percentage(property: Property<String>, option: String): Int {
        val raw = property.orNull ?: return 0
        val value = raw.removeSuffix("%").trim().toIntOrNull()
            ?: throw GradleException("--$option takes a whole number of percent, not \"$raw\"")
        if (value !in 0..90) throw GradleException("--$option must be between 0 and 90, not $value")
        return value
    }

    // -- scoring ------------------------------------------------------------------------------------

    private fun scoreClasses(score: Score, old: MatchSide, fixture: Scramble, matched: Map<String, String>) {
        for (node in old.nodes) {
            val expected = fixture.survivingName(node.name)
            val actual = matched[node.name]
            score.count("classes", node.name, expected, actual)
        }
    }

    private fun scoreMembers(score: Score, old: MatchSide, fixture: Scramble, new: MatchSide, result: alaphant.build.mappings.MatchResult) {
        for (node in old.nodes) {
            for (field in node.fields.orEmpty()) {
                val key = Keys.field(node.name, field.name, field.desc)
                score.count("fields", key, fixture.survivingField(node.name, field.name, field.desc), result.fields[key])
            }
            for (method in node.methods.orEmpty()) {
                if (method.name == "<init>" || method.name == "<clinit>") continue
                val key = Keys.method(node.name, method.name, method.desc)
                score.count("methods", key, fixture.survivingMethod(node.name, method.name, method.desc), result.methods[key])
            }
        }
    }

    /**
     * The transfer is where an override group's representative changes name, so the ledger's method
     * IDs are the part most likely to land somewhere subtly wrong. Checked against the ledger the
     * repository actually has, in memory.
     */
    private fun scoreTransfer(score: Score, old: MatchSide, new: MatchSide, fixture: Scramble, result: alaphant.build.mappings.MatchResult) {
        val file = ledgerFile.get().asFile
        if (!file.isFile) return
        val ledger = Ledger.read(file)
        val from = charlesVersion.get()
        val to = "$from-scrambled"

        MatchTransfer(ledger, from, to, result, old, new).apply()

        for (entry in ledger.forKind("methods")) {
            val official = entry.official[from] ?: continue
            val acceptable = old.groups.groups[official].orEmpty()
                .mapNotNull { fixture.survivingMethodKey(it) }
                .mapNotNull { new.groups.rootOf(it) }
                .toSet()
            score.countAny("ledger methods", entry.id, acceptable, entry.official[to])
        }

        for (kind in listOf("classes", "fields")) {
            for (entry in ledger.forKind(kind)) {
                val official = entry.official[from] ?: continue
                val expected = when (kind) {
                    "classes" -> fixture.survivingName(official)
                    else -> fixture.survivingField(
                        official.substringBeforeLast(':').substringBeforeLast('.'),
                        official.substringBeforeLast(':').substringAfterLast('.'),
                        official.substringAfterLast(':'),
                    )
                }
                score.count("ledger $kind", entry.id, expected, entry.official[to])
            }
        }
    }

    private class Score(private val provenance: Map<String, String>) {
        private val correct = LinkedHashMap<String, Int>()
        private val missed = LinkedHashMap<String, Int>()
        private val expected = LinkedHashMap<String, Int>()
        private val wrongExamples = mutableListOf<String>()
        private val missedElements = LinkedHashMap<String, MutableList<String>>()
        var wrong = 0
            private set

        fun count(kind: String, element: String, want: String?, got: String?) =
            countAny(kind, element, setOfNotNull(want), got)

        /**
         * Scores against every answer that is defensible, not just one.
         *
         * Override groups need this. Deleting the method that linked a group splits it in two, and
         * the ID can only follow one part -- whichever part the matcher placed. Both are correct;
         * there is no fact of the matter about which half "is" the original group.
         */
        fun countAny(kind: String, element: String, acceptable: Set<String>, got: String?) {
            val want = acceptable.firstOrNull()
            if (want == null) {
                // Deleted from the fixture, so there is nothing to match. Claiming one is still wrong.
                if (got != null) {
                    wrong++
                    if (wrongExamples.size < LIMIT) {
                        wrongExamples += "$kind $element -> $got (was deleted)${by(element)}"
                    }
                }
                return
            }
            expected.merge(kind, 1, Int::plus)
            when {
                got in acceptable -> correct.merge(kind, 1, Int::plus)
                got == null -> {
                    missed.merge(kind, 1, Int::plus)
                    missedElements.getOrPut(kind) { mutableListOf() }.add(element)
                }
                else -> {
                    wrong++
                    if (wrongExamples.size < LIMIT) wrongExamples += "$kind $element -> $got, wanted $want${by(element)}"
                }
            }
        }

        fun report(log: (String) -> Unit) {
            for (kind in expected.keys) {
                val total = expected.getValue(kind)
                val right = correct[kind] ?: 0
                log(
                    "  ${kind.padEnd(16)} ${right.toString().padStart(6)} of ${total.toString().padStart(6)} " +
                        "matched (${right * 100 / total.coerceAtLeast(1)}%), " +
                        "${(missed[kind] ?: 0).toString().padStart(5)} missed"
                )
            }
            log("")
            log(if (wrong == 0) "  no wrong matches" else "  $wrong WRONG MATCHES")
            wrongExamples.forEach { log("    $it") }
        }

        /** The full miss list, so a drop in recall can be looked at rather than guessed about. */
        private fun by(element: String): String = provenance[element]?.let { "  [$it]" } ?: ""

        fun detail(reasons: Map<String, String>): String = buildString {
            val tally = missedElements["classes"].orEmpty().groupingBy { reasons[it] ?: "unknown" }.eachCount()
            appendLine("Why classes did not match")
            tally.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.value.toString().padStart(5)}  ${it.key}") }
            appendLine()

            for ((kind, elements) in missedElements) {
                appendLine("$kind: ${elements.size} not matched")
                elements.forEach { appendLine("  $it${reasons[it]?.let { why -> "  -- $why" } ?: ""}") }
                appendLine()
            }
        }

        private companion object {
            const val LIMIT = 25
        }
    }
}

/**
 * The installed jar with every obfuscated name put through the obfuscator again, plus optional
 * deletions. Knows what it did, so the match can be scored against it.
 */
private class Scramble(
    jar: Jar,
    private val classifier: NameClassifier,
    dropClassPercent: Int,
    dropMethodPercent: Int,
) {
    private val nodes: List<ClassNode> = jar.nodes
    private val byName: Map<String, ClassNode> = jar.byName
    private val groups = MethodGroups.compute(nodes, classifier)

    private val packages = HashMap<String, String>()
    private val classes = LinkedHashMap<String, String>()
    private val methodNames = HashMap<String, String>()
    private val fieldNames = HashMap<String, String>()

    /** Declared before the init block that fills the rename map, which is what draws from it. */
    private val counters = HashMap<Int, Int>()

    /** Deterministic: the same jar always produces the same fixture, so a failure can be re-run. */
    private val droppedClasses: Set<String>
    private val droppedMethods: Set<String>

    val kept: Int get() = nodes.size - droppedClasses.size

    init {
        val obfuscated = nodes.map { it.name }.filter { simpleIsObfuscated(it) }
        droppedClasses = pick(obfuscated, dropClassPercent)

        val allMethods = nodes.flatMap { node ->
            node.methods.orEmpty()
                .filter { it.name != "<init>" && it.name != "<clinit>" && classifier.isObfuscatedMemberName(it.name) }
                .map { Keys.method(node.name, it.name, it.desc) }
        }
        droppedMethods = pick(allMethods, dropMethodPercent)

        nodes.forEach { renameClass(it.name) }
        renameMembers()
    }

    private fun <T : Comparable<T>> pick(from: List<T>, percent: Int): Set<T> {
        if (percent == 0) return emptySet()
        return from.sorted()
            .filterIndexed { index, _ -> index * percent / 100 < (index + 1) * percent / 100 }
            .toSet()
    }

    // -- the rename map -------------------------------------------------------------------------------

    private fun renameClass(official: String): String = classes.getOrPut(official) {
        val nested = official.lastIndexOf('$')
        if (nested < 0) {
            val pkg = official.substringBeforeLast('/', "")
            val simple = official.substringAfterLast('/')
            val mapped = if (classifier.isObfuscatedClassName(simple)) fresh(CLASS) else simple
            if (pkg.isEmpty()) mapped else "${renamePackage(pkg)}/$mapped"
        } else {
            val tail = official.substring(nested + 1)
            val mapped = when {
                tail.all(Char::isDigit) -> tail
                classifier.isObfuscatedClassName(tail) -> fresh(CLASS)
                else -> tail
            }
            "${renameClass(official.substring(0, nested))}\$$mapped"
        }
    }

    private fun renamePackage(path: String): String {
        val out = StringBuilder()
        var prefix = ""
        for (segment in path.split('/')) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            if (out.isNotEmpty()) out.append('/')
            out.append(
                if (classifier.isObfuscatedPackageSegment(segment)) packages.getOrPut(prefix) { fresh(PACKAGE) }
                else segment
            )
        }
        return out.toString()
    }

    /** One fresh name per override group, or the scrambled jar would not link. */
    private fun renameMembers() {
        for ((root, members) in groups.groups) {
            val name = fresh(MEMBER)
            members.forEach { methodNames[it] = name }
            check(root in members)
        }
        for (node in nodes) {
            for (field in node.fields.orEmpty()) {
                if (!classifier.isObfuscatedMemberName(field.name)) continue
                fieldNames[Keys.field(node.name, field.name, field.desc)] = fresh(MEMBER)
            }
        }
    }

    private fun simpleIsObfuscated(internalName: String): Boolean {
        val tail = internalName.substringAfterLast('/').substringAfterLast('$')
        return !tail.all(Char::isDigit) && classifier.isObfuscatedClassName(tail)
    }

    // -- what the answer should be -------------------------------------------------------------------

    /** The class' name in the fixture, or null if the fixture does not have it. */
    fun survivingName(official: String): String? =
        if (official in droppedClasses) null else classes[official]

    fun survivingField(owner: String, name: String, desc: String): String? {
        if (owner in droppedClasses) return null
        val target = classes[owner] ?: return null
        val renamed =
            if (classifier.isObfuscatedMemberName(name)) fieldNames[Keys.field(owner, name, desc)] ?: return null
            else name
        return Keys.field(target, renamed, mapDescriptor(desc))
    }

    fun survivingMethod(owner: String, name: String, desc: String): String? {
        if (owner in droppedClasses) return null
        val key = Keys.method(owner, name, desc)
        if (key in droppedMethods) return null
        val target = classes[owner] ?: return null
        val renamed =
            if (classifier.isObfuscatedMemberName(name)) methodNames[key] ?: return null
            else name
        return Keys.method(target, renamed, mapDescriptor(desc))
    }

    fun survivingMethodKey(key: String): String? {
        val desc = "(" + key.substringAfter('(')
        val head = key.substringBefore("(")
        return survivingMethod(head.substringBeforeLast('.'), head.substringAfterLast('.'), desc)
    }

    private fun mapDescriptor(desc: String): String =
        MatchSide.DESCRIPTOR_CLASS.replace(desc) { "L${classes[it.groupValues[1]] ?: it.groupValues[1]};" }

    // -- writing the fixture ---------------------------------------------------------------------------

    fun writeTo(file: File) {
        val ancestors = HashMap<String, List<String>>()
        fun hierarchy(name: String): List<String> = ancestors.getOrPut(name) {
            val node = byName[name] ?: return@getOrPut emptyList()
            val parents = listOfNotNull(node.superName) + node.interfaces.orEmpty()
            parents.filter { it in byName }.flatMap { listOf(it) + hierarchy(it) }
        }

        val remapper = object : Remapper(Opcodes.ASM9) {
            override fun map(internalName: String): String = classes[internalName] ?: internalName

            override fun mapMethodName(owner: String, name: String, desc: String): String {
                for (candidate in listOf(owner) + hierarchy(owner)) {
                    methodNames[Keys.method(candidate, name, desc)]?.let { return it }
                }
                return name
            }

            override fun mapFieldName(owner: String, name: String, desc: String): String {
                for (candidate in listOf(owner) + hierarchy(owner)) {
                    fieldNames[Keys.field(candidate, name, desc)]?.let { return it }
                }
                return name
            }
        }

        file.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for (node in nodes) {
                if (node.name in droppedClasses) continue
                node.methods = node.methods.orEmpty()
                    .filterNot { Keys.method(node.name, it.name, it.desc) in droppedMethods }
                    .toMutableList()

                val writer = ClassWriter(0)
                node.accept(ClassRemapper(writer, remapper))
                zip.putNextEntry(java.util.zip.ZipEntry(classes.getValue(node.name) + ".class"))
                zip.write(writer.toByteArray())
                zip.closeEntry()
            }
        }
    }

    // -- fresh names -------------------------------------------------------------------------------------

    /**
     * Four letters, which is what Charles' obfuscator emits and what [NameClassifier] recognises: a
     * capital in a package segment, and no capital anywhere else so the classifier cannot mistake a
     * fixture name for one of the readable names it is told to leave alone.
     */
    private tailrec fun fresh(kind: Int): String {
        var index = counters.merge(kind, 1, Int::plus)!! + kind * OFFSET
        val letters = CharArray(4)
        for (position in 3 downTo 0) {
            letters[position] = 'a' + index % 26
            index /= 26
        }
        val candidate = String(letters)
        val name = if (kind == PACKAGE) candidate.replaceFirstChar(Char::uppercaseChar) else candidate

        val obfuscated = when (kind) {
            PACKAGE -> classifier.isObfuscatedPackageSegment(name)
            CLASS -> classifier.isObfuscatedClassName(name)
            else -> classifier.isObfuscatedMemberName(name)
        }
        // `read`, `size` and friends are on the readable list, and one of them coming out of the
        // generator would give the fixture an element the classifier treats as never-obfuscated.
        return if (obfuscated) name else fresh(kind)
    }

    private companion object {
        const val PACKAGE = 0
        const val CLASS = 1
        const val MEMBER = 2

        /** Keeps the three name spaces from colliding, which would make a failure hard to read. */
        const val OFFSET = 100_000
    }
}
