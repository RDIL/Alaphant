package alaphant.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Allocates the `intermediary` namespace for one Charles version: official -> intermediary tiny v2,
 * plus the allocation ledger.
 *
 * Scheme (§3.1 of `docs/charles5-plan.md`): readable package segments and class names pass through
 * verbatim, obfuscated ones become `pkg_N` / `class_N`, members become `field_N` / `method_N` off
 * separate counters. Only renamed elements are written, so a class appears when it or one of its
 * members is renamed.
 *
 * **An override group has to share one intermediary name.** Give `class_5.method_10` and the
 * `class_3.method_10` it overrides separate IDs and the remap breaks the override, surfacing as an
 * unrelated `AbstractMethodError`. So methods are grouped by name+descriptor over the internal type
 * hierarchy and allocated per group; over-grouping is harmless, under-grouping is not.
 *
 * External overrides need nothing special: the obfuscator could not rename them either, which is why
 * `run`, `read` and `size` are still readable in the jar.
 */
abstract class GenerateIntermediaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val officialJar: RegularFileProperty

    /** `mappings/readable-names.txt` — short official names that are real identifiers. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readableNames: RegularFileProperty

    @get:Input
    abstract val charlesVersion: Property<String>

    @get:OutputFile
    abstract val outputMappings: RegularFileProperty

    /** Read as well as written: existing allocations are carried forward, never reissued. */
    @get:OutputFile
    abstract val ledgerFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val version = charlesVersion.get()
        val classifier = readableNames.orNull
            ?.let { NameClassifier.read(it.asFile) }
            ?: NameClassifier(emptySet(), emptySet())

        val nodes = readClassNodes(officialJar.get().asFile)
        logger.lifecycle("Allocating intermediary names for Charles $version (${nodes.size} classes)")

        val ledger = Ledger.read(ledgerFile.get().asFile)
        val allocation = Allocator(classifier, ledger, version, nodes).allocate()

        writeTiny(outputMappings.get().asFile, allocation)
        allocation.writeLedger(ledger, ledgerFile.get().asFile, version)

        with(allocation.stats) {
            logger.lifecycle("  packages  ${renamedPackages.pad()} renamed  ${readablePackages.pad()} readable")
            logger.lifecycle("  classes   ${renamedClasses.pad()} renamed  ${readableClasses.pad()} readable")
            logger.lifecycle("  fields    ${renamedFields.pad()} renamed  ${readableFields.pad()} readable")
            logger.lifecycle("  methods   ${renamedMethods.pad()} renamed  ${readableMethods.pad()} readable  ($methodGroups override groups)")
        }
        logger.lifecycle("Wrote ${outputMappings.get().asFile.name} and ${ledgerFile.get().asFile.name}")
    }

    private fun Int.pad() = toString().padStart(5)

    private fun readClassNodes(jar: File): List<ClassNode> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.endsWith(".class") && it.name != ModuleInfoRemapper.MODULE_INFO }
            .map { entry ->
                ClassNode().also { node ->
                    zip.getInputStream(entry).use { ClassReader(it).accept(node, ClassReader.SKIP_CODE) }
                }
            }
            .sortedBy { it.name }
            .toList()
    }

    private fun writeTiny(file: File, allocation: Allocation) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write("tiny\t2\t0\tofficial\tintermediary\n")
            for (cls in allocation.classes) {
                out.write("c\t${cls.official}\t${cls.intermediary}\n")
                for (field in cls.fields) {
                    out.write("\tf\t${field.desc}\t${field.official}\t${field.intermediary}\n")
                }
                for (method in cls.methods) {
                    out.write("\tm\t${method.desc}\t${method.official}\t${method.intermediary}\n")
                }
            }
        }
    }
}

/** One class' allocation. Only renamed members are listed. */
internal data class ClassAllocation(
    val official: String,
    val intermediary: String,
    val fields: List<MemberAllocation>,
    val methods: List<MemberAllocation>,
)

internal data class MemberAllocation(val official: String, val desc: String, val intermediary: String)

/** A member ID as the ledger records it: the ID, and where it came from in the official namespace. */
internal data class MemberRecord(
    val id: String,
    val owner: String,
    val name: String,
    val desc: String,
)

internal class Allocation(
    val classes: List<ClassAllocation>,
    val stats: Stats,
    private val packageMap: Map<String, String>,
    private val classMap: Map<String, String>,
    private val fields: Map<String, MemberRecord>,
    private val methods: Map<String, MemberRecord>,
    private val classNames: Map<String, String>,
    private val fingerprints: Map<String, String>,
    private val counters: Map<String, Int>,
    private val mapDescriptor: (String) -> String,
) {
    class Stats(
        val renamedPackages: Int, val readablePackages: Int,
        val renamedClasses: Int, val readableClasses: Int,
        val renamedFields: Int, val readableFields: Int,
        val renamedMethods: Int, val readableMethods: Int,
        val methodGroups: Int,
    )

    fun writeLedger(ledger: Ledger, file: File, version: String) {
        ledger.replace("packages", counters.getValue("packages"), packageMap.map { (official, id) ->
            Ledger.Entry(id = id, official = linkedMapOf(version to official), firstSeen = version)
        }.sortedBy(::numericSuffix))

        ledger.replace("classes", counters.getValue("classes"), classMap.map { (official, id) ->
            Ledger.Entry(
                id = id,
                official = linkedMapOf(version to official),
                firstSeen = version,
                fingerprint = fingerprints[official],
            )
        }.sortedBy(::numericSuffix))

        for ((kind, records) in listOf("fields" to fields, "methods" to methods)) {
            ledger.replace(kind, counters.getValue(kind), records.map { (key, record) ->
                Ledger.Entry(
                    id = record.id,
                    official = linkedMapOf(version to key),
                    firstSeen = version,
                    owner = classNames[record.owner] ?: record.owner,
                    desc = mapDescriptor(record.desc),
                )
            }.sortedBy(::numericSuffix))
        }

        ledger.write(file, version)
    }

    private fun numericSuffix(entry: Ledger.Entry): Int =
        entry.id.substringAfterLast('_').toIntOrNull() ?: 0
}

/**
 * Does the actual allocation. Everything is driven off collections in sorted order, so the same jar
 * always produces the same file.
 */
internal class Allocator(
    private val classifier: NameClassifier,
    ledger: Ledger,
    private val version: String,
    private val nodes: List<ClassNode>,
) {
    private val existing = Ledger.KINDS.associateWith { ledger.lookup(it, version) }
    private val counters = Ledger.KINDS.associateWith { ledger.counter(it) }.toMutableMap()

    private val packageMap = LinkedHashMap<String, String>()
    private val classMap = LinkedHashMap<String, String>()
    private val fieldRecords = LinkedHashMap<String, MemberRecord>()
    private val methodRecords = LinkedHashMap<String, MemberRecord>()
    private val mappedClassNames = HashMap<String, String>()
    private val allPackages = LinkedHashSet<String>()

    private val internal: Map<String, ClassNode> = nodes.associateBy { it.name }

    fun allocate(): Allocation {
        allocatePackages()
        nodes.forEach { mapClassName(it.name) }

        val methodIds = allocateMethods()
        val classes = nodes.mapNotNull { node -> allocateClass(node, methodIds) }

        val fingerprints = nodes.associate { it.name to fingerprint(it) }
        val totalMethods = nodes.sumOf { node ->
            node.methods.orEmpty().count { it.name != "<init>" && it.name != "<clinit>" }
        }
        val totalFields = nodes.sumOf { it.fields.orEmpty().size }
        val renamedFields = classes.sumOf { it.fields.size }
        val renamedMethods = classes.sumOf { it.methods.size }

        return Allocation(
            classes = classes,
            stats = Allocation.Stats(
                renamedPackages = packageMap.size,
                readablePackages = allPackages.size - packageMap.size,
                renamedClasses = nodes.count { mappedClassNames.getValue(it.name) != it.name },
                readableClasses = nodes.count { mappedClassNames.getValue(it.name) == it.name },
                renamedFields = renamedFields,
                readableFields = totalFields - renamedFields,
                renamedMethods = renamedMethods,
                readableMethods = totalMethods - renamedMethods,
                methodGroups = methodRecords.size,
            ),
            packageMap = packageMap,
            classMap = classMap,
            fields = fieldRecords,
            methods = methodRecords,
            classNames = mappedClassNames,
            fingerprints = fingerprints,
            counters = counters,
            mapDescriptor = ::mapDescriptor,
        )
    }

    // -- packages -------------------------------------------------------------------------------

    private fun allocatePackages() {
        val packages = nodes.map { it.name.substringBeforeLast('/', "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        for (path in packages) {
            var officialPrefix = ""
            for (segment in path.split('/')) {
                officialPrefix = if (officialPrefix.isEmpty()) segment else "$officialPrefix/$segment"
                allPackages.add(officialPrefix)
                if (classifier.isObfuscatedPackageSegment(segment)) {
                    packageMap.getOrPut(officialPrefix) { allocate("packages", officialPrefix) }
                }
            }
        }
    }

    private fun mapPackage(path: String): String {
        if (path.isEmpty()) return path
        val out = StringBuilder()
        var officialPrefix = ""
        for (segment in path.split('/')) {
            officialPrefix = if (officialPrefix.isEmpty()) segment else "$officialPrefix/$segment"
            if (out.isNotEmpty()) out.append('/')
            out.append(packageMap[officialPrefix] ?: segment)
        }
        return out.toString()
    }

    // -- classes --------------------------------------------------------------------------------

    private fun mapClassName(official: String): String = mappedClassNames.getOrPut(official) {
        val nested = official.lastIndexOf('$')
        if (nested < 0) {
            val pkg = official.substringBeforeLast('/', "")
            val simple = official.substringAfterLast('/')
            val mapped = if (classifier.isObfuscatedClassName(simple)) {
                classMap.getOrPut(official) { allocate("classes", official) }
            } else {
                simple
            }
            if (pkg.isEmpty()) mapped else "${mapPackage(pkg)}/$mapped"
        } else {
            val outer = mapClassName(official.substring(0, nested))
            val tail = official.substring(nested + 1)
            val mapped = when {
                tail.all { it.isDigit() } -> tail
                classifier.isObfuscatedClassName(tail) -> classMap.getOrPut(official) { allocate("classes", official) }
                else -> tail
            }
            "$outer\$$mapped"
        }
    }

    private fun allocateClass(node: ClassNode, methodIds: Map<String, String>): ClassAllocation? {
        val fields = node.fields.orEmpty()
            .sortedWith(compareBy({ it.name }, { it.desc }))
            .filter { classifier.isObfuscatedMemberName(it.name) }
            .map { field ->
                val key = "${node.name}.${field.name}:${field.desc}"
                val id = allocate("fields", key)
                fieldRecords[key] = MemberRecord(id, node.name, field.name, field.desc)
                MemberAllocation(field.name, field.desc, id)
            }

        val methods = node.methods.orEmpty()
            .filter { it.name != "<init>" && it.name != "<clinit>" }
            .sortedWith(compareBy({ it.name }, { it.desc }))
            .filter { classifier.isObfuscatedMemberName(it.name) }
            .map { method ->
                MemberAllocation(
                    method.name,
                    method.desc,
                    methodIds.getValue(methodKey(node.name, method.name, method.desc)),
                )
            }

        val name = mappedClassNames.getValue(node.name)
        if (name == node.name && fields.isEmpty() && methods.isEmpty()) return null

        return ClassAllocation(node.name, name, fields, methods)
    }

    // -- methods --------------------------------------------------------------------------------

    /** Union-find over the override closure; one ID per group. */
    private fun allocateMethods(): Map<String, String> {
        val parent = HashMap<String, String>()

        fun find(key: String): String {
            var root = key
            while (parent[root] != null && parent[root] != root) root = parent.getValue(root)
            parent[key] = root
            return root
        }

        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            // Keep the lexicographically smaller key as the root, so grouping is reproducible.
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }

        val ancestorCache = HashMap<String, Set<String>>()
        fun ancestors(name: String): Set<String> = ancestorCache.getOrPut(name) {
            val node = internal[name] ?: return@getOrPut emptySet()
            val out = LinkedHashSet<String>()
            for (parentName in listOfNotNull(node.superName) + node.interfaces.orEmpty()) {
                if (parentName in internal) {
                    out.add(parentName)
                    out.addAll(ancestors(parentName))
                }
            }
            out
        }

        for (node in nodes) {
            for (method in node.methods.orEmpty()) {
                if (method.name == "<init>" || method.name == "<clinit>") continue
                if (!classifier.isObfuscatedMemberName(method.name)) continue
                val key = methodKey(node.name, method.name, method.desc)
                parent.putIfAbsent(key, key)
                for (ancestor in ancestors(node.name)) {
                    val declared = internal.getValue(ancestor).methods.orEmpty()
                        .any { it.name == method.name && it.desc == method.desc }
                    if (declared) union(key, methodKey(ancestor, method.name, method.desc))
                }
            }
        }

        // Allocate walking the jar in sorted order, so the numbering follows the file.
        val ids = HashMap<String, String>()
        for (node in nodes) {
            for (method in node.methods.orEmpty().sortedWith(compareBy({ it.name }, { it.desc }))) {
                if (method.name == "<init>" || method.name == "<clinit>") continue
                if (!classifier.isObfuscatedMemberName(method.name)) continue
                val key = methodKey(node.name, method.name, method.desc)
                val root = find(key)
                val record = methodRecords.getOrPut(root) {
                    val owner = root.substringBefore(".${method.name}")
                    MemberRecord(allocate("methods", root), owner, method.name, method.desc)
                }
                ids[key] = record.id
            }
        }
        return ids
    }

    private fun methodKey(owner: String, name: String, desc: String) = "$owner.$name$desc"

    // -- fingerprints ---------------------------------------------------------------------------

    /**
     * Shape hash over intermediary supertypes and member descriptors, so it stays comparable across a
     * Charles bump: it answers "did this element actually change?".
     */
    private fun fingerprint(node: ClassNode): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun feed(value: String) = digest.update(value.toByteArray())

        feed(node.superName?.let { mappedClassNames[it] ?: it }.orEmpty())
        node.interfaces.orEmpty().map { mappedClassNames[it] ?: it }.sorted().forEach(::feed)
        node.fields.orEmpty().map { mapDescriptor(it.desc) }.sorted().forEach(::feed)
        node.methods.orEmpty().map { mapDescriptor(it.desc) }.sorted().forEach(::feed)

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Translates an official descriptor into the intermediary namespace. */
    private fun mapDescriptor(desc: String): String = DESCRIPTOR_CLASS.replace(desc) { match ->
        val official = match.groupValues[1]
        "L${mappedClassNames[official] ?: mapUnknownClass(official)};"
    }

    /** A type that is not in the jar keeps its name, but its package may still be renamed. */
    private fun mapUnknownClass(internalName: String): String {
        val pkg = internalName.substringBeforeLast('/', "")
        if (pkg.isEmpty()) return internalName
        return "${mapPackage(pkg)}/${internalName.substringAfterLast('/')}"
    }

    // -- allocation -----------------------------------------------------------------------------

    private fun allocate(kind: String, officialKey: String): String {
        existing.getValue(kind)[officialKey]?.let { return it }
        val next = counters.getValue(kind) + 1
        counters[kind] = next
        return "${PREFIXES.getValue(kind)}_$next"
    }

    private companion object {
        val PREFIXES = mapOf("packages" to "pkg", "classes" to "class", "fields" to "field", "methods" to "method")
        val DESCRIPTOR_CLASS = Regex("""L([^;]+);""")
    }
}
