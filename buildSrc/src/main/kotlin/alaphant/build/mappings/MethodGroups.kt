package alaphant.build.mappings

import org.objectweb.asm.tree.ClassNode

/**
 * The override closures of one jar: every obfuscated method that has to share a single intermediary
 * name, grouped, with a deterministic representative per group.
 *
 * Splitting an override group across two intermediary names breaks the override, and the failure
 * surfaces as an `AbstractMethodError` a long way from the mapping that caused it. Two things have
 * to agree about the grouping, which is why it lives here rather than inside either of them:
 *
 *  - [alaphant.build.tasks.GenerateIntermediaryTask] allocates one ID per group.
 *  - [VersionMatcher]'s transfer has to land that ID on the *new* jar's group for the same methods,
 *    and the group representative is picked by name — so it is generally a different member of the
 *    group on each side of a Charles bump. Recomputing the grouping on the new jar is the only way
 *    to know which key the ledger should record.
 *
 * Over-grouping is harmless: two unrelated methods sharing an intermediary name costs nothing.
 * Under-grouping is not, so the closure is deliberately generous.
 */
internal class MethodGroups private constructor(
    /** `owner.name+desc` -> the key representing its override group. */
    private val roots: Map<String, String>,
) {
    fun rootOf(key: String): String? = roots[key]

    /** Group representative -> every key in the group, the representative included. */
    val groups: Map<String, List<String>> by lazy(LazyThreadSafetyMode.NONE) {
        roots.entries.groupBy({ it.value }, { it.key })
    }

    val size: Int get() = roots.size

    companion object {
        fun key(owner: String, name: String, desc: String): String = "$owner.$name$desc"

        fun compute(nodes: List<ClassNode>, classifier: NameClassifier): MethodGroups {
            val internal: Map<String, ClassNode> = nodes.associateBy { it.name }
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

            // Group over each class' whole supertype closure, not just its own declarations. A class
            // can implement an interface method with a method it inherits from a superclass that
            // knows nothing about the interface -- neither declaration is an ancestor of the other,
            // so the only thing linking them is the class that brings them together. Grouping from
            // the subclass' point of view catches that; grouping from the declaration's does not,
            // and the two names then drift apart into an AbstractMethodError.
            for (node in nodes) {
                val closure = ancestors(node.name) + node.name
                val declarations = HashMap<String, MutableList<String>>()

                for (owner in closure) {
                    for (method in internal[owner]?.methods.orEmpty()) {
                        if (method.name == "<init>" || method.name == "<clinit>") continue
                        if (!classifier.isObfuscatedMemberName(method.name)) continue
                        val key = key(owner, method.name, method.desc)
                        parent.putIfAbsent(key, key)
                        declarations.getOrPut("${method.name}${method.desc}") { mutableListOf() }.add(key)
                    }
                }

                for (group in declarations.values) {
                    group.drop(1).forEach { union(group.first(), it) }
                }
            }

            return MethodGroups(parent.keys.associateWith(::find))
        }
    }
}
