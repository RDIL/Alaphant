package alaphant.agent

import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.service.MixinService
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

object AlaphantAgent {
    private const val MIXIN_CONFIG = "mixin.charles.json"

    private val EXCLUDED = listOf(
        "alaphant/agent/",
        "org/spongepowered/",
        "org/objectweb/asm/",
        "com/google/",
        "kotlin/",
    )

    @Suppress("unused")
    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        System.setProperty("mixin.env.disableRefMap", "true")

        val transformer = bootstrapMixin()
        instrumentation.addTransformer(MixinTransformerAdapter(instrumentation, transformer), true)
        log("agent ready, ${Mixins.getConfigs().size} mixin config(s)")
    }

    private fun bootstrapMixin(): IMixinTransformer {
        MixinBootstrap.init()

        val service = MixinService.getService() as? AlaphantMixinService
            ?: error(
                "Mixin picked a different service: ${MixinService.getService().name}. " +
                    "Check META-INF/services in the agent jar."
            )
        service.gotoPhase(MixinEnvironment.Phase.DEFAULT)

        Mixins.addConfiguration(MIXIN_CONFIG)
        return service.createTransformer()
    }

    private fun readUnnamedModule(instrumentation: Instrumentation, module: Module) {
        val unnamed = AlaphantAgent::class.java.classLoader.unnamedModule
        if (module.canRead(unnamed) || !instrumentation.isModifiableModule(module)) return
        instrumentation.redefineModule(module, setOf(unnamed), emptyMap(), emptyMap(), emptySet(), emptyMap())
        log("module ${module.name} now reads the unnamed module")
    }

    private fun log(message: String) = println("[alaphant] $message")

    private class MixinTransformerAdapter(
        private val instrumentation: Instrumentation,
        private val transformer: IMixinTransformer,
    ) : ClassFileTransformer {
        override fun transform(
            module: Module?,
            loader: ClassLoader?,
            className: String?,
            beingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            if (className == null || !isCandidate(loader, className)) return null

            return try {
                val patched = transformer.transformClass(
                    MixinEnvironment.getCurrentEnvironment(),
                    className.replace('/', '.'),
                    classfileBuffer,
                )
                if (patched === classfileBuffer) return null
                if (module != null && module.isNamed) {
                    readUnnamedModule(instrumentation, module)
                }
                patched
            } catch (t: Throwable) {
                System.err.println("[alaphant] mixin transform failed for $className")
                t.printStackTrace()
                null
            }
        }

        /**
         * The JDK (boot and platform loaders) is never a mixin target and makes up most of what an
         * agent sees, so it is filtered out before Mixin parses anything.
         */
        private fun isCandidate(loader: ClassLoader?, className: String): Boolean =
            loader != null &&
                loader !== ClassLoader.getPlatformClassLoader() &&
                EXCLUDED.none { className.startsWith(it) }
    }
}
