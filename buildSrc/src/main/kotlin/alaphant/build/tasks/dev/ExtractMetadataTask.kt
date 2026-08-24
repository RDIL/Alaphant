package alaphant.build.tasks.dev

import com.google.gson.stream.JsonWriter
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.util.zip.ZipFile

abstract class ExtractMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val out = outputDir.get().asFile.also { it.mkdirs() }
        val nodes = readClasses()

        // owner.name:desc -> list of "fromClass#fromMethod:desc"
        val xrefs = HashMap<String, MutableList<String>>()

        JsonWriter(out.resolve("classes.json").bufferedWriter()).use { w ->
            w.setIndent("  ")
            w.beginObject().name("classes").beginArray()

            for (cn in nodes) {
                val strings = LinkedHashSet<String>()

                w.beginObject()
                w.name("name").value(cn.name)
                w.name("access").value(cn.access)
                w.name("superName").value(cn.superName)
                w.name("interfaces").beginArray()
                cn.interfaces?.forEach { w.value(it) }
                w.endArray()

                w.name("fields").beginArray()
                for (f in cn.fields.orEmpty()) {
                    w.beginObject()
                        .name("name").value(f.name)
                        .name("desc").value(f.desc)
                        .name("access").value(f.access)
                    f.signature?.let { w.name("signature").value(it) }
                    w.endObject()
                }
                w.endArray()

                w.name("methods").beginArray()
                for (m in cn.methods.orEmpty()) {
                    w.beginObject()
                        .name("name").value(m.name)
                        .name("desc").value(m.desc)
                        .name("access").value(m.access)
                    m.signature?.let { w.name("signature").value(it) }
                    w.endObject()

                    val from = "${cn.name}#${m.name}:${m.desc}"
                    for (insn in m.instructions ?: continue) {
                        when (insn) {
                            is LdcInsnNode -> (insn.cst as? String)
                                ?.takeIf { it.isNotBlank() && it.length in 2..300 }
                                ?.let { strings.add(it) }
                            is MethodInsnNode ->
                                xrefs.getOrPut("${insn.owner}.${insn.name}:${insn.desc}") { mutableListOf() }.add(from)
                            is FieldInsnNode ->
                                xrefs.getOrPut("${insn.owner}.${insn.name}:${insn.desc}") { mutableListOf() }.add(from)
                        }
                    }
                }
                w.endArray()

                w.name("strings").beginArray()
                strings.forEach { w.value(it) }
                w.endArray()

                w.endObject()
            }

            w.endArray().endObject()
        }

        JsonWriter(out.resolve("xrefs.json").bufferedWriter()).use { w ->
            w.setIndent("  ")
            w.beginObject().name("xrefs").beginObject()
            for ((target, from) in xrefs.entries.sortedBy { it.key }) {
                w.name(target).beginArray()
                from.distinct().sorted().forEach { w.value(it) }
                w.endArray()
            }
            w.endObject().endObject()
        }

        logger.lifecycle("Indexed ${nodes.size} classes, ${xrefs.size} referenced members -> $out")
    }

    private fun readClasses(): List<ClassNode> = ZipFile(inputJar.get().asFile).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.endsWith(".class") && it.name != "module-info.class" }
            .map { entry ->
                ClassNode().also { node ->
                    zip.getInputStream(entry).use { ClassReader(it).accept(node, ClassReader.SKIP_FRAMES) }
                }
            }
            .sortedBy { it.name }
            .toList()
    }
}
