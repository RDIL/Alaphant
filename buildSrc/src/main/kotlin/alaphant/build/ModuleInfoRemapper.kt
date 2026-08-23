package alaphant.build

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Restores and remaps `module-info.class`, which tiny-remapper discards.
 */
internal object ModuleInfoRemapper {
    const val MODULE_INFO = "module-info.class"

    /** Returns the original `module-info.class` bytes, or null if the jar has no module descriptor. */
    fun read(jar: Path): ByteArray? = ZipFile(jar.toFile()).use { zip ->
        zip.getEntry(MODULE_INFO)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
    }

    /** Remaps [bytes] across one namespace hop and writes the result into [outputJar]. */
    fun remapInto(outputJar: Path, bytes: ByteArray, mappings: Path, from: String, to: String): Int {
        val classes = classMap(mappings, from, to)
        val remapper = NameRemapper(classes)

        val writer = ClassWriter(0)
        ClassReader(bytes).accept(ClassRemapper(writer, remapper), 0)

        FileSystems.newFileSystem(outputJar).use { fs ->
            Files.write(fs.getPath(MODULE_INFO), writer.toByteArray())
        }

        return classes.size
    }

    private fun classMap(mappings: Path, from: String, to: String): Map<String, String> {
        val tree = MemoryMappingTree()
        MappingReader.read(mappings, tree)

        val fromId = namespaceId(tree, from)
        val toId = namespaceId(tree, to)

        return tree.classes.mapNotNull { cls ->
            val src = cls.getName(fromId) ?: return@mapNotNull null
            val dst = cls.getName(toId) ?: return@mapNotNull null
            if (src == dst) null else src to dst
        }.toMap()
    }

    private fun namespaceId(tree: MemoryMappingTree, namespace: String): Int {
        if (namespace == tree.srcNamespace) return MappingTreeView.SRC_NAMESPACE_ID
        val id = tree.getNamespaceId(namespace)
        require(id != MappingTreeView.NULL_NAMESPACE_ID) {
            "Namespace '$namespace' is not in the mapping file (have: ${tree.srcNamespace}, ${tree.dstNamespaces})"
        }
        return id
    }

    private class NameRemapper(private val classes: Map<String, String>) : Remapper(Opcodes.ASM9) {
        /**
         * Package renames implied by the class renames. Needed because the module descriptor lists
         * packages independently of classes, and the intermediary namespace renames obfuscated
         * package segments (`com/charlesproxy/macos/MkAr` -> `com/charlesproxy/macos/pkg_1`).
         */
        private val packages: Map<String, String> = classes.entries
            .mapNotNull { (src, dst) ->
                val srcPackage = src.substringBeforeLast('/', "")
                val dstPackage = dst.substringBeforeLast('/', "")
                if (srcPackage.isEmpty() || srcPackage == dstPackage) null else srcPackage to dstPackage
            }
            .toMap()

        override fun map(internalName: String): String = classes[internalName] ?: internalName

        override fun mapPackageName(name: String): String = packages[name] ?: name

        /** The module keeps its name; only its contents are remapped. */
        override fun mapModuleName(name: String): String = name
    }
}
