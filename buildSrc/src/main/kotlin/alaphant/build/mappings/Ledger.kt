package alaphant.build.mappings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.text.iterator

/**
 * The intermediary ID allocation ledger: an ID, once handed out, never means anything else.
 *
 * The tiny file records official -> intermediary for one version; the ledger adds what survives an
 * upgrade — the counters, so a retired number is never reused, and per ID the version that
 * introduced it plus a shape fingerprint.
 */
internal class Ledger private constructor(
    val counters: MutableMap<String, Int>,
    val entries: MutableMap<String, MutableList<Entry>>,
) {
    /**
     * One allocated ID. [official] is keyed by Charles version, so a bump adds a column rather than
     * overwriting history.
     */
    data class Entry(
        val id: String,
        val official: MutableMap<String, String>,
        val firstSeen: String,
        /** Owning class, in the intermediary namespace. Members only. */
        val owner: String? = null,
        /** Descriptor in the intermediary namespace. Members only. */
        val desc: String? = null,
        /** Shape hash over the class' intermediary supertypes and member descriptors. Classes only. */
        val fingerprint: String? = null,
    )

    /** `official name -> id`, for the given kind and version, so a re-run reuses what it allocated. */
    fun lookup(kind: String, version: String): Map<String, String> =
        entries[kind].orEmpty().mapNotNull { entry -> entry.official[version]?.let { it to entry.id } }.toMap()

    fun counter(kind: String): Int = counters[kind] ?: 0

    /**
     * Folds one version's allocation into the ledger.
     *
     * Both halves of this are load-bearing. [fresh] carries the official names for the version being
     * generated, and an ID that already exists keeps its `firstSeen` and the official names recorded
     * for every *other* version — that is the column-per-version history the three-namespace scheme
     * runs on. An ID that [fresh] does **not** mention is kept as it stands rather than dropped: it
     * belongs to an element that this version no longer has, and its record is the only evidence of
     * what it used to be. Dropping it would also lose the fingerprint the matcher needs on the next
     * bump, and would quietly retire the ID while the counter still reserves it.
     */
    fun replace(kind: String, counter: Int, fresh: List<Entry>) {
        counters[kind] = counter
        val merged = entries[kind].orEmpty().associateByTo(LinkedHashMap()) { it.id }
        for (entry in fresh) {
            val old = merged[entry.id]
            merged[entry.id] = if (old == null) entry else entry.copy(
                official = LinkedHashMap(old.official).apply { putAll(entry.official) },
                firstSeen = old.firstSeen,
            )
        }
        entries[kind] = merged.values.sortedBy { it.id.numericSuffix() }.toMutableList()
    }

    /** `id -> entry`, for a caller recording a whole version's worth of names. */
    fun index(kind: String): Map<String, Entry> = entries[kind].orEmpty().associateBy { it.id }

    fun forKind(kind: String): List<Entry> = entries[kind].orEmpty()

    /** Every Charles version the ledger has official names for, oldest allocation first. */
    val versions: Set<String>
        get() = KINDS.flatMapTo(LinkedHashSet()) { kind -> entries[kind].orEmpty().flatMap { it.official.keys } }

    val isEmpty: Boolean get() = KINDS.all { entries[it].isNullOrEmpty() }

    private fun String.numericSuffix(): Int = substringAfterLast('_').toIntOrNull() ?: 0

    fun write(file: File, generatedFor: String) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write("{\n")
            out.write("  \"formatVersion\": 1,\n")
            out.write("  \"generatedFor\": ${quote(generatedFor)},\n")
            out.write("  \"counters\": {")
            out.write(counters.entries.sortedBy { it.key }.joinToString(", ") { "${quote(it.key)}: ${it.value}" })
            out.write("},\n")

            for ((index, kind) in KINDS.withIndex()) {
                out.write("  ${quote(kind)}: [\n")
                val list = entries[kind].orEmpty()
                for ((i, entry) in list.withIndex()) {
                    out.write("    {")
                    out.write("\"id\": ${quote(entry.id)}")
                    out.write(", \"firstSeen\": ${quote(entry.firstSeen)}")
                    entry.owner?.let { out.write(", \"owner\": ${quote(it)}") }
                    entry.desc?.let { out.write(", \"desc\": ${quote(it)}") }
                    out.write(", \"official\": {")
                    out.write(entry.official.entries.joinToString(", ") { "${quote(it.key)}: ${quote(it.value)}" })
                    out.write("}")
                    entry.fingerprint?.let { out.write(", \"fingerprint\": ${quote(it)}") }
                    out.write("}")
                    if (i != list.lastIndex) out.write(",")
                    out.write("\n")
                }
                out.write("  ]")
                if (index != KINDS.lastIndex) out.write(",")
                out.write("\n")
            }
            out.write("}\n")
        }
    }

    companion object {
        val KINDS = listOf("packages", "classes", "fields", "methods")

        fun empty() = Ledger(mutableMapOf(), mutableMapOf())

        fun read(file: File): Ledger {
            if (!file.isFile) return empty()

            val root = JsonParser.parseString(file.readText()).asJsonObject
            val counters = mutableMapOf<String, Int>()
            root.getAsJsonObject("counters")?.entrySet()?.forEach { counters[it.key] = it.value.asInt }

            val entries = mutableMapOf<String, MutableList<Entry>>()
            for (kind in KINDS) {
                val array = root.getAsJsonArray(kind) ?: continue
                entries[kind] = array.map { element ->
                    val obj = element.asJsonObject
                    Entry(
                        id = obj["id"].asString,
                        official = obj.getAsJsonObject("official").entrySet()
                            .associateTo(LinkedHashMap()) { it.key to it.value.asString },
                        firstSeen = obj["firstSeen"].asString,
                        owner = obj.optString("owner"),
                        desc = obj.optString("desc"),
                        fingerprint = obj.optString("fingerprint"),
                    )
                }.toMutableList()
            }

            return Ledger(counters, entries)
        }

        private fun JsonObject.optString(name: String): String? =
            get(name)?.takeIf { !it.isJsonNull }?.asString

        private fun quote(value: String): String = buildString {
            append('"')
            for (ch in value) when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(ch)
            }
            append('"')
        }
    }
}
