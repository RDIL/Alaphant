package alaphant.build.tasks.mappings

import alaphant.build.mappings.ModuleInfoRemapper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.util.zip.ZipFile

/**
 * Links the remapped jar without running it.
 *
 * `validateMappings` checks that the named store is well formed; nothing checked that the jar it
 * produces still works. Two ways it can silently stop working, both of which shipped undetected
 * until Charles was launched:
 *
 * - **A rename moves a class between packages.** Package-private access is a runtime-package
 *   relationship, so moving one class out of its package and leaving its neighbours behind turns a
 *   legal reference into an `IllegalAccessError` -- thrown when the reference is first resolved,
 *   which can be hours into a session.
 * - **An override group is split.** If an interface method and the superclass method that
 *   implements it get different intermediary IDs, the implementing class silently stops
 *   implementing the interface, and the JVM raises `AbstractMethodError` on the first call.
 *
 * Both are invisible to a jar-level diff and to class loading -- the JVM resolves lazily, so a class
 * can define cleanly and fail an hour later. This walks every reference eagerly instead.
 */
abstract class CheckLinkageTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    /** Charles' dependencies, so supertypes outside the jar still resolve. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraries: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val jar = inputJar.get().asFile
        val own = readClasses(jar, withCode = true)
        val index = Types(own, libraries.files.flatMap { readClasses(it, withCode = false) })

        val problems = own.flatMap { node ->
            checkAccess(node, index) + checkImplementations(node, index)
        }

        logger.lifecycle("Linked ${own.size} classes against ${index.size} known types")
        if (problems.isEmpty()) return

        val shown = problems.take(MAX_REPORTED)
        throw GradleException(
            buildString {
                appendLine("${problems.size} linkage problem(s) in ${jar.name}:")
                shown.forEach { appendLine("    $it") }
                if (problems.size > shown.size) appendLine("    ... and ${problems.size - shown.size} more")
                append("These are mapping faults, not Charles faults: the same jar links before remapping.")
            }
        )
    }

    /**
     * Every reference a class makes to a non-public type in another package.
     *
     * Only in-jar types are checked. A type from a library cannot have moved, and one from the JDK
     * is never renamed, so neither can become inaccessible through a mapping.
     */
    private fun checkAccess(node: ClassNode, index: Types): List<String> {
        val from = node.name.packageName()

        return referencedTypes(node)
            .distinct()
            .mapNotNull { referenced ->
                val target = index.own(referenced) ?: return@mapNotNull null
                if (target.access and Opcodes.ACC_PUBLIC != 0) return@mapNotNull null
                if (target.name.packageName() == from) return@mapNotNull null
                "${node.name} references package-private ${target.name} from another package"
            }
            .toList()
    }

    /** Abstract methods a concrete class inherits and never implements. */
    private fun checkImplementations(node: ClassNode, index: Types): List<String> {
        if (node.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE) != 0) return emptyList()

        // An unresolved supertype could be holding the implementation, so there is nothing to say
        // about this class. Rare in practice -- the JDK resolves through the runtime image.
        val closure = supertypes(node, index) ?: return emptyList()
        val implemented = HashSet<String>()
        val abstracts = LinkedHashMap<String, String>()

        for (type in closure) {
            for (method in type.methods.orEmpty()) {
                if (method.name == "<init>" || method.name == "<clinit>") continue
                if (method.access and Opcodes.ACC_STATIC != 0) continue
                val signature = "${method.name}${method.desc}"
                if (method.access and Opcodes.ACC_ABSTRACT != 0) {
                    abstracts.putIfAbsent(signature, type.name)
                } else {
                    implemented.add(signature)
                }
            }
        }

        return abstracts
            .filterKeys { it !in implemented }
            .map { (signature, owner) -> "${node.name} never implements $signature declared by $owner" }
    }

    /** The class and everything above it, or null if any of it is unresolvable. */
    private fun supertypes(node: ClassNode, index: Types): List<ClassNode>? {
        val seen = LinkedHashMap<String, ClassNode>()
        var complete = true

        fun walk(current: ClassNode) {
            if (seen.put(current.name, current) != null) return
            for (parent in listOfNotNull(current.superName) + current.interfaces.orEmpty()) {
                val resolved = index[parent]
                if (resolved == null) complete = false else walk(resolved)
            }
        }

        walk(node)
        return seen.values.toList().takeIf { complete }
    }

    /**
     * Everything the check can see: the jar under test, Charles' dependencies, and the JDK.
     *
     * The JDK matters more than it looks. Half of Charles' GUI inherits from Swing, and without
     * `java.awt.Component` in view every one of those classes looks like it fails to implement
     * `getX()`. Its classes are read out of the running runtime image, which is accurate enough --
     * the check only ever asks whether a method exists.
     */
    private class Types(own: List<ClassNode>, libraries: List<ClassNode>) {
        private val classes = HashMap<String, ClassNode?>(own.size * 4)
        private val ownNames = own.mapTo(HashSet()) { it.name }

        init {
            libraries.forEach { classes.putIfAbsent(it.name, it) }
            own.forEach { classes[it.name] = it }
        }

        val size: Int get() = classes.size

        /** Null for a type that is not in the jar under test, and so cannot have been remapped. */
        fun own(name: String): ClassNode? = if (name in ownNames) classes[name] else null

        operator fun get(name: String): ClassNode? = classes.getOrPut(name) {
            ClassLoader.getSystemResourceAsStream("$name.class")?.use { stream ->
                ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG) }
            }
        }
    }

    /**
     * The types a class references in a way the JVM access-checks.
     *
     * Deliberately narrower than "every type named in the class file". A type mentioned only inside
     * a descriptor is never resolved on its own account -- `getstatic Type.FOO : LType$5;` links
     * fine even though `Type$5` is package-private and elsewhere, because field resolution matches
     * the descriptor as text and resolves only the owner. Checking descriptors reports 54 problems
     * against the untouched Charles jar; checking resolution sites reports none.
     */
    private fun referencedTypes(node: ClassNode): Sequence<String> = sequence {
        node.superName?.let { yield(it) }
        node.interfaces.orEmpty().forEach { yield(it) }

        for (method in node.methods.orEmpty()) {
            for (instruction in method.instructions ?: continue) {
                when (instruction) {
                    // new, anewarray, checkcast, instanceof
                    is TypeInsnNode -> yieldAll(internalName(instruction.desc))
                    is FieldInsnNode -> yieldAll(internalName(instruction.owner))
                    is MethodInsnNode -> yieldAll(internalName(instruction.owner))
                    is MultiANewArrayInsnNode -> yieldAll(typesIn(instruction.desc))
                    // A class literal resolves the class.
                    is LdcInsnNode -> (instruction.cst as? Type)?.let { yieldAll(typesIn(it.descriptor)) }
                }
            }
        }
    }

    /** Array owners appear as descriptors (`[Lfoo;`) where a plain internal name is usual. */
    private fun internalName(owner: String): List<String> =
        if (owner.startsWith("[")) typesIn(owner) else listOf(owner)

    private fun typesIn(desc: String): List<String> =
        DESCRIPTOR_CLASS.findAll(desc).map { it.groupValues[1] }.toList()

    private fun String.packageName(): String = substringBeforeLast('/', "")

    private fun readClasses(archive: File, withCode: Boolean): List<ClassNode> {
        if (!archive.isFile) return emptyList()
        val flags = if (withCode) ClassReader.SKIP_DEBUG else ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG
        return ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".class") && !it.name.endsWith(ModuleInfoRemapper.MODULE_INFO) }
                .map { entry ->
                    ClassNode().also { node ->
                        zip.getInputStream(entry).use { ClassReader(it).accept(node, flags) }
                    }
                }
                .toList()
        }
    }

    private companion object {
        const val MAX_REPORTED = 25
        val DESCRIPTOR_CLASS = Regex("""L([^;<]+);""")
    }
}
