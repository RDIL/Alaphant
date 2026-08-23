package alaphant.build.mappings

/**
 * Writes a [MatchResult] into the ledger as one more official-name column.
 *
 * This is the whole point of matching, and it is deliberately the only thing that touches the ledger:
 * `generateIntermediary` reuses an ID when `lookup(kind, version)` already knows the official name it
 * belongs to, so recording `official[newVersion]` here is exactly what makes the next generate reuse
 * every ID instead of allocating a fresh one. No change to the allocator is needed, and nothing else
 * in the pipeline has to know a match happened.
 *
 * Three things make the transfer more than a map copy:
 *
 *  - **A member that stopped being obfuscated gets no ID.** The allocator only names what the
 *    obfuscator hid, so recording a readable name against an ID would leave a column that never
 *    matches anything. Those are dropped and counted.
 *  - **Method IDs belong to override groups, not methods.** The ledger records the group's
 *    representative, which is chosen by name — so it is usually a *different* member of the group on
 *    each side of an upgrade. The whole old group has to be mapped through and the new
 *    representative recomputed, which is why [MethodGroups] is shared with the allocator.
 *  - **Two IDs must never land on one element.** If a version merges two override groups, or two
 *    packages, both IDs are left behind rather than one silently winning.
 */
internal class MatchTransfer(
    private val ledger: Ledger,
    private val oldVersion: String,
    private val newVersion: String,
    private val match: MatchResult,
    private val old: MatchSide,
    private val new: MatchSide,
) {
    class Outcome {
        /** Per kind: entries the old version had an official name for. */
        val known = LinkedHashMap<String, Int>()

        /** Per kind: entries that carried forward. */
        val carried = LinkedHashMap<String, Int>()

        /** Per kind: entries whose element is no longer obfuscated, so the new version gives it no ID. */
        val readable = LinkedHashMap<String, Int>()

        /** Per kind: IDs that did not carry, in ledger order. */
        val lost = LinkedHashMap<String, MutableList<String>>()

        /** Elements the new version has that the old one did not, per kind. */
        val added = LinkedHashMap<String, Int>()

        /** Two IDs competing for one element, which leaves both behind. */
        val conflicts = mutableListOf<String>()

        internal fun lostFor(kind: String): MutableList<String> = lost.getOrPut(kind) { mutableListOf() }
    }

    private val classifier = old.classifier

    fun apply(): Outcome {
        val outcome = Outcome()
        transferClasses(outcome)
        transferPackages(outcome)
        transferFields(outcome)
        transferMethods(outcome)
        countAdditions(outcome)
        return outcome
    }

    // -- classes ------------------------------------------------------------------------------------

    private fun transferClasses(outcome: Outcome) {
        val proposals = LinkedHashMap<String, String>()
        for (entry in ledger.forKind("classes")) {
            val from = entry.official[oldVersion] ?: continue
            outcome.known.merge("classes", 1, Int::plus)

            val to = match.classes[from]
            if (to == null) {
                outcome.lostFor("classes").add(entry.id)
                continue
            }
            if (!allocatesClassId(to)) {
                outcome.readable.merge("classes", 1, Int::plus)
                outcome.lostFor("classes").add(entry.id)
                continue
            }
            proposals[entry.id] = to
        }
        record("classes", proposals, outcome)
    }

    /**
     * Whether the allocator would hand the new name an ID at all — the same test `mapClassName` makes.
     * A nested name is judged on its own tail, and a positional inner (`Foo$1`) never gets one.
     */
    private fun allocatesClassId(internalName: String): Boolean {
        val nested = internalName.lastIndexOf('$')
        if (nested < 0) return classifier.isObfuscatedClassName(internalName.substringAfterLast('/'))
        val tail = internalName.substring(nested + 1)
        return !tail.all(Char::isDigit) && classifier.isObfuscatedClassName(tail)
    }

    // -- packages -----------------------------------------------------------------------------------

    /**
     * Package IDs follow their classes. Every matched class pair votes for the pairing of each
     * package *prefix* along its path, not just the innermost one, because the allocator gives an ID
     * to every obfuscated prefix and `com/xk72/serialization/MkAr/MkAr` has two of them.
     */
    private fun transferPackages(outcome: Outcome) {
        val votes = HashMap<String, MutableMap<String, Int>>()
        for ((from, to) in match.classes) {
            val fromPath = from.substringBeforeLast('/', "")
            val toPath = to.substringBeforeLast('/', "")
            if (fromPath.isEmpty() || toPath.isEmpty()) continue
            val fromSegments = fromPath.split('/')
            val toSegments = toPath.split('/')
            if (fromSegments.size != toSegments.size) continue

            for (depth in fromSegments.indices) {
                val fromPrefix = fromSegments.subList(0, depth + 1).joinToString("/")
                val toPrefix = toSegments.subList(0, depth + 1).joinToString("/")
                votes.getOrPut(fromPrefix) { HashMap() }.merge(toPrefix, 1, Int::plus)
            }
        }

        val chosen = HashMap<String, String>()
        for ((from, tally) in votes) {
            val total = tally.values.sum()
            val winner = tally.maxByOrNull { it.value } ?: continue
            if (winner.value * 5 >= total * 4) chosen[from] = winner.key
        }

        val proposals = LinkedHashMap<String, String>()
        for (entry in ledger.forKind("packages")) {
            val from = entry.official[oldVersion] ?: continue
            outcome.known.merge("packages", 1, Int::plus)

            val to = chosen[from]
            if (to == null) {
                outcome.lostFor("packages").add(entry.id)
                continue
            }
            if (!classifier.isObfuscatedPackageSegment(to.substringAfterLast('/'))) {
                // The segment is readable in the new version, so it needs no `pkg_N` — and if the
                // name is now known it belongs in `mappings/package-names.txt` instead.
                outcome.readable.merge("packages", 1, Int::plus)
                outcome.lostFor("packages").add(entry.id)
                continue
            }
            proposals[entry.id] = to
        }
        record("packages", proposals, outcome)
    }

    // -- fields -------------------------------------------------------------------------------------

    private fun transferFields(outcome: Outcome) {
        val proposals = LinkedHashMap<String, String>()
        for (entry in ledger.forKind("fields")) {
            val from = entry.official[oldVersion] ?: continue
            outcome.known.merge("fields", 1, Int::plus)

            val to = match.fields[from]
            if (to == null) {
                outcome.lostFor("fields").add(entry.id)
                continue
            }
            if (!classifier.isObfuscatedMemberName(fieldName(to))) {
                outcome.readable.merge("fields", 1, Int::plus)
                outcome.lostFor("fields").add(entry.id)
                continue
            }
            proposals[entry.id] = to
        }
        record("fields", proposals, outcome)
    }

    /** `owner.name:desc` — a descriptor holds no `:` and an internal name holds no `.`. */
    private fun fieldName(key: String): String = key.substringBeforeLast(':').substringAfterLast('.')

    // -- methods ------------------------------------------------------------------------------------

    private fun transferMethods(outcome: Outcome) {
        val proposals = LinkedHashMap<String, String>()
        for (entry in ledger.forKind("methods")) {
            val from = entry.official[oldVersion] ?: continue
            outcome.known.merge("methods", 1, Int::plus)

            // The ID belongs to the whole override group, so any member of it can carry the answer.
            val members = old.groups.groups[from] ?: listOf(from)
            val matched = members.mapNotNull { match.methods[it] }
            if (matched.isEmpty()) {
                outcome.lostFor("methods").add(entry.id)
                continue
            }

            val roots = matched.mapNotNull { new.groups.rootOf(it) }.distinct()
            when {
                roots.isEmpty() -> {
                    outcome.readable.merge("methods", 1, Int::plus)
                    outcome.lostFor("methods").add(entry.id)
                }
                roots.size > 1 -> {
                    outcome.conflicts += "${entry.id}: its override group split across " +
                        roots.joinToString(", ") { it.substringBeforeLast('(') }
                    outcome.lostFor("methods").add(entry.id)
                }
                else -> proposals[entry.id] = roots.single()
            }
        }
        record("methods", proposals, outcome)
    }

    // -- writing ------------------------------------------------------------------------------------

    /** Applies the proposals that are the sole claim on their element; reports and drops the rest. */
    private fun record(kind: String, proposals: Map<String, String>, outcome: Outcome) {
        val claims = proposals.values.groupingBy { it }.eachCount()
        val index = ledger.index(kind)
        for ((id, official) in proposals) {
            if (claims.getValue(official) > 1) {
                val rivals = proposals.filterValues { it == official }.keys
                outcome.conflicts += "$kind $official: claimed by ${rivals.joinToString(", ")}"
                outcome.lostFor(kind).add(id)
                continue
            }
            index[id]?.official?.put(newVersion, official)
            outcome.carried.merge(kind, 1, Int::plus)
        }
    }

    // -- what is new ----------------------------------------------------------------------------------

    /** Elements the new jar has that no old ID claimed, i.e. what `generateIntermediary` will allocate. */
    private fun countAdditions(outcome: Outcome) {
        val takenPackages = ledger.forKind("packages").mapNotNullTo(HashSet()) { it.official[newVersion] }
        outcome.added["packages"] = new.nodes
            .flatMap { node -> node.name.substringBeforeLast('/', "").split('/').scan("") { path, segment ->
                if (path.isEmpty()) segment else "$path/$segment"
            } }
            .filter { it.isNotEmpty() && classifier.isObfuscatedPackageSegment(it.substringAfterLast('/')) }
            .distinct()
            .count { it !in takenPackages }

        val takenClasses = ledger.forKind("classes").mapNotNullTo(HashSet()) { it.official[newVersion] }
        outcome.added["classes"] = new.nodes.count { allocatesClassId(it.name) && it.name !in takenClasses }

        val takenFields = ledger.forKind("fields").mapNotNullTo(HashSet()) { it.official[newVersion] }
        outcome.added["fields"] = new.nodes.sumOf { node ->
            node.fields.orEmpty().count {
                classifier.isObfuscatedMemberName(it.name) &&
                    Keys.field(node.name, it.name, it.desc) !in takenFields
            }
        }

        val takenMethods = ledger.forKind("methods").mapNotNullTo(HashSet()) { it.official[newVersion] }
        outcome.added["methods"] = new.groups.groups.keys.count { it !in takenMethods }
    }
}
