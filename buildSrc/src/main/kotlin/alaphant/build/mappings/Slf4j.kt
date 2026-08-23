package alaphant.build.mappings

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Charles compiles with Lombok's `@Slf4j`, which passes the original fully-qualified class name to
 * `LoggerFactory.getLogger(String)` as a constant. The obfuscator rewrites the class but not the
 * string, so the class states its own original name — and states the *same* name in every Charles
 * release, which makes it an anchor a version bump cannot move.
 */
internal object Slf4j {
    private const val FACTORY = "org/slf4j/LoggerFactory"
    private const val GET_LOGGER_STRING = "(Ljava/lang/String;)Lorg/slf4j/Logger;"

    /** The literal this class logs under, if it names exactly one. */
    fun literalOf(node: ClassNode): String? {
        val found = mutableSetOf<String>()
        for (method in node.methods.orEmpty()) {
            val instructions = method.instructions ?: continue
            for (insn in instructions) {
                if (insn !is MethodInsnNode) continue
                if (insn.owner != FACTORY || insn.name != "getLogger" || insn.desc != GET_LOGGER_STRING) continue
                ((insn.previous as? LdcInsnNode)?.cst as? String)?.let { found.add(it) }
            }
        }
        return found.singleOrNull()
    }
}
