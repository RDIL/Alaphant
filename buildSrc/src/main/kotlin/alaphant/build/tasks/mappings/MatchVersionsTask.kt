package alaphant.build.tasks.mappings

import alaphant.build.mappings.IntermediaryIndex
import alaphant.build.mappings.Ledger
import alaphant.build.mappings.MatchResult
import alaphant.build.mappings.MatchSide
import alaphant.build.mappings.MatchTransfer
import alaphant.build.mappings.NameClassifier
import alaphant.build.mappings.NamedStore
import alaphant.build.mappings.VersionMatcher
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
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
import java.io.File
import kotlin.collections.iterator

/**
 * Carries the intermediary namespace from one Charles release to the next.
 *
 * This is the task that makes a Charles upgrade survivable. `mappings/named/` is keyed on
 * intermediary names, so a bump costs nothing *provided* the new jar's elements keep the intermediary
 * IDs the old jar's elements had. Nothing else establishes that: `generateIntermediary` reuses an ID
 * only when the ledger already records the official name it belongs to *for the version being
 * generated*, and for a version nobody has seen before that is nothing at all.
 *
 * So the order on an upgrade is:
 *
 * ```
 * ./gradlew matchVersions --previous-jar=/path/to/old/Charles.app/Contents/Java
 * ./gradlew generateIntermediary
 * ./gradlew validateMappings checkLinkage
 * ```
 *
 * Run in the other order and every element gets a fresh ID, every name in `mappings/named/` points at
 * an intermediary name that no longer exists, and the only way back is `git checkout mappings/`.
 * `generateIntermediary` refuses to run on an unmatched version for exactly that reason.
 *
 * The old jar is a required input, and worth saying plainly: **keep a copy of the install you mapped
 * against.** Matching is jar-to-jar; the ledger alone does not describe the old version in enough
 * detail to stand in for it.
 *
 * Everything the matcher could not place is listed in the report, headed by the number that actually
 * matters — how many classes with hand-written names failed to carry. [VersionMatcher] documents the
 * evidence each pass runs on and why none of them guess.
 */
abstract class MatchVersionsTask : DefaultTask() {
    /** The new Charles jar: the install the build is currently pointed at. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val officialJar: RegularFileProperty

    @get:Input
    abstract val newVersion: Property<String>

    /**
     * The jar of the release the mappings were made against, or the install directory holding it.
     * Falls back to the `alaphant.previousCharlesInstall` Gradle property.
     */
    @get:Input
    @get:Optional
    @get:Option(option = "previous-jar", description = "The previous charles.jar, or the install directory holding it.")
    abstract val previousJar: Property<String>

    /** Which version that jar is. Only needed when the ledger knows about more than one. */
    @get:Input
    @get:Optional
    @get:Option(option = "previous-version", description = "The Charles version the previous jar is.")
    abstract val previousVersion: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "report-only", description = "Work out the match and report it, without touching the ledger.")
    abstract val reportOnly: Property<Boolean>

    @get:Input
    @get:Optional
    @get:Option(
        option = "order-fallback",
        description = "Match members nothing else separates by declaration order. On by default.",
    )
    abstract val orderFallback: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readableNames: RegularFileProperty

    /** Read and rewritten in place: the ledger is committed content, not a build output. */
    @get:Internal
    abstract val ledgerFile: RegularFileProperty

    /** Only read, and only to say which unmatched classes had names on them. */
    @get:Internal
    abstract val namedDir: DirectoryProperty

    /** `mappings/intermediary/`, for the previous version's official -> intermediary file. */
    @get:Internal
    abstract val intermediaryDir: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun match() {
        val ledger = Ledger.read(ledgerFile.get().asFile)
        val to = newVersion.get()
        val from = resolvePreviousVersion(ledger, to)
        val previous = resolvePreviousJar()

        if (ledger.forKind("classes").any { it.firstSeen == to }) {
            throw GradleException(
                "The ledger already has IDs first seen in $to, so generateIntermediary has run for " +
                    "this version without a match. Those IDs are fresh allocations, and matching on " +
                    "top of them would leave two official names on one ID.\n" +
                    "Restore the ledger first:  git checkout mappings/ledger.json"
            )
        }

        val classifier = readableNames.orNull
            ?.let { NameClassifier.read(it.asFile) }
            ?: NameClassifier(emptySet(), emptySet())

        logger.lifecycle("Matching Charles $from -> $to")
        logger.lifecycle("  old  ${previous.absolutePath}")
        logger.lifecycle("  new  ${officialJar.get().asFile.absolutePath}")

        val oldSide = MatchSide(Jar.read(previous), classifier)
        val newSide = MatchSide(Jar.read(officialJar.get().asFile), classifier)
        logger.lifecycle("  ${oldSide.nodes.size} classes -> ${newSide.nodes.size} classes")

        val result = VersionMatcher(
            old = oldSide,
            new = newSide,
            positional = orderFallback.getOrElse(true),
            log = { logger.lifecycle(it) },
        ).match()

        val outcome = MatchTransfer(ledger, from, to, result, oldSide, newSide).apply()
        val atRisk = namesAtRisk(ledger, from, outcome)

        val text = Report(from, to, result, outcome, atRisk, oldSide, newSide).render()
        report.get().asFile.also { it.parentFile?.mkdirs() }.writeText(text)

        if (reportOnly.getOrElse(false)) {
            logger.lifecycle("")
            logger.lifecycle("--report-only: the ledger was not touched.")
        } else {
            ledger.write(ledgerFile.get().asFile, to)
            logger.lifecycle("")
            logger.lifecycle("Wrote ${ledgerFile.get().asFile}")
        }

        logger.lifecycle("")
        logger.lifecycle(summary(outcome, atRisk))
        logger.lifecycle("Full report: ${report.get().asFile}")
    }

    // -- inputs ---------------------------------------------------------------------------------------

    private fun resolvePreviousJar(): File {
        val configured = previousJar.orNull
            ?: throw GradleException(
                buildString {
                    appendLine("No previous Charles jar. Matching is jar-to-jar, so the release the mappings")
                    appendLine("were made against has to be on disk — the ledger does not describe it in enough")
                    appendLine("detail to stand in for it.")
                    appendLine()
                    appendLine("Pass it directly:")
                    appendLine("  ./gradlew matchVersions --previous-jar=/path/to/Charles.app/Contents/Java")
                    appendLine()
                    appendLine("or set it once in gradle.properties:")
                    append("  alaphant.previousCharlesInstall=/path/to/Charles.app/Contents/Java")
                }
            )

        val file = File(configured)
        val jar = if (file.isDirectory) File(file, "charles.jar") else file
        if (!jar.isFile) throw GradleException("No charles.jar at ${jar.absolutePath}")
        return jar
    }

    private fun resolvePreviousVersion(ledger: Ledger, to: String): String {
        previousVersion.orNull?.let { return it }

        val known = ledger.versions - to
        return when {
            known.isEmpty() -> throw GradleException(
                "The ledger has no allocations to carry forward. If this is the first version being " +
                    "mapped, run generateIntermediary directly."
            )
            known.size == 1 -> known.single()
            else -> throw GradleException(
                "The ledger knows about ${known.joinToString(", ")}. Say which one the previous jar is:\n" +
                    "  ./gradlew matchVersions --previous-version=${known.last()}"
            )
        }
    }

    // -- what the upgrade puts at risk -------------------------------------------------------------------

    /**
     * The classes that failed to carry *and* have a name on them. Every other number in the report is
     * context for this one: an unmatched class nobody has named yet costs a fresh ID and nothing else,
     * where an unmatched class with a name costs the work that produced the name.
     */
    private fun namesAtRisk(ledger: Ledger, from: String, outcome: Outcome): List<Risk> {
        val storeDir = namedDir.get().asFile
        if (!storeDir.isDirectory) return emptyList()
        val intermediaryFile = File(intermediaryDir.get().asFile, "$from.tiny")
        if (!intermediaryFile.isFile) return emptyList()

        val store = NamedStore.load(storeDir)
        val index = IntermediaryIndex.read(intermediaryFile)
        val byId = ledger.forKind("classes").associateBy { it.id }

        return outcome.lost["classes"].orEmpty().mapNotNull { id ->
            val official = byId[id]?.official?.get(from) ?: return@mapNotNull null
            val intermediary = index.classOf(official) ?: return@mapNotNull null
            val named = store.existing(intermediary) ?: return@mapNotNull null
            val name = named.name ?: return@mapNotNull null
            val members = named.fields.values.count { it.name != null } +
                named.methods.values.count { it.name != null }
            Risk(id, intermediary, name, members)
        }
    }

    internal data class Risk(val id: String, val intermediary: String, val name: String, val namedMembers: Int)

    private fun summary(outcome: Outcome, atRisk: List<Risk>): String = buildString {
        val classes = outcome.carried["classes"] ?: 0
        val known = outcome.known["classes"] ?: 0
        appendLine("$classes of $known class IDs carried forward.")
        if (atRisk.isEmpty()) {
            append("No named class lost its mapping.")
        } else {
            append("${atRisk.size} named class(es) did not carry forward — see the report before committing.")
        }
    }
}

private typealias Outcome = MatchTransfer.Outcome

/** The human-readable half of the answer. The ledger diff is the other half. */
private class Report(
    private val from: String,
    private val to: String,
    private val result: MatchResult,
    private val outcome: Outcome,
    private val atRisk: List<MatchVersionsTask.Risk>,
    private val old: MatchSide,
    private val new: MatchSide,
) {
    fun render(): String = buildString {
        appendLine("Charles $from -> $to")
        appendLine("=".repeat(40))
        appendLine()
        appendLine("${old.nodes.size} classes in, ${new.nodes.size} classes out, settled in ${result.rounds} round(s).")
        appendLine()

        appendLine("Carried forward")
        appendLine("---------------")
        for (kind in listOf("packages", "classes", "fields", "methods")) {
            val known = outcome.known[kind] ?: 0
            val carried = outcome.carried[kind] ?: 0
            val percent = if (known == 0) 0 else carried * 100 / known
            appendLine(
                "  ${kind.padEnd(9)} ${carried.toString().padStart(5)} of ${known.toString().padStart(5)}" +
                    "  ${percent.toString().padStart(3)}%" +
                    "  ${(outcome.added[kind] ?: 0).toString().padStart(5)} new" +
                    readableNote(kind)
            )
        }
        appendLine()

        appendLine("Class matches by evidence")
        appendLine("-------------------------")
        for ((pass, count) in result.classPasses) appendLine("  ${pass.padEnd(20)} ${count.toString().padStart(5)}")
        if (result.rejected > 0) {
            appendLine("  ${"rejected".padEnd(20)} ${result.rejected.toString().padStart(5)}  (wrong kind, or a different package tree)")
        }
        appendLine()

        appendLine("Member matches by evidence")
        appendLine("--------------------------")
        for ((pass, count) in result.memberPasses.entries.sortedBy { it.key }) {
            appendLine("  ${pass.padEnd(28)} ${count.toString().padStart(5)}")
        }
        appendLine()
        appendLine("  \"declaration order\" is the one pass with no hard evidence behind it: same class,")
        appendLine("  same descriptor, same modifiers, and the same number of them on each side. Spot-check")
        appendLine("  those against the decompiled output if anything downstream looks wrong.")
        appendLine()

        if (outcome.conflicts.isNotEmpty()) {
            appendLine("Conflicts (both IDs left behind rather than guessing)")
            appendLine("----------------------------------------------------")
            outcome.conflicts.take(CONFLICT_LIMIT).forEach { appendLine("  $it") }
            if (outcome.conflicts.size > CONFLICT_LIMIT) {
                appendLine("  ... and ${outcome.conflicts.size - CONFLICT_LIMIT} more")
            }
            appendLine()
        }

        appendLine("Named work that did not carry forward")
        appendLine("-------------------------------------")
        if (atRisk.isEmpty()) {
            appendLine("  None. Every class with a name kept its intermediary ID.")
        } else {
            appendLine("  ${atRisk.size} class(es). Each one needs its name re-established against the new jar,")
            appendLine("  or the match improving until it carries.")
            appendLine()
            for (risk in atRisk.sortedByDescending { it.namedMembers }) {
                val members = if (risk.namedMembers > 0) "  (${risk.namedMembers} named members)" else ""
                appendLine("  ${risk.id.padEnd(12)} ${risk.name.padEnd(36)} ${risk.intermediary}$members")
            }
        }
        appendLine()

        appendLine("Unmatched IDs")
        appendLine("-------------")
        for (kind in listOf("packages", "classes", "fields", "methods")) {
            val lost = outcome.lost[kind].orEmpty()
            if (lost.isEmpty()) continue
            appendLine("  ${kind}: ${lost.size}")
            appendLine("    ${lost.take(ID_LIMIT).joinToString(" ")}${if (lost.size > ID_LIMIT) " ..." else ""}")
        }
    }

    private fun readableNote(kind: String): String {
        val readable = outcome.readable[kind] ?: 0
        return if (readable == 0) "" else "  ($readable no longer obfuscated)"
    }

    private companion object {
        const val CONFLICT_LIMIT = 40
        const val ID_LIMIT = 30
    }
}
