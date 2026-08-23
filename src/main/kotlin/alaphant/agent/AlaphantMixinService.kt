@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package alaphant.agent

import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual
import org.spongepowered.asm.launch.platform.container.IContainerHandle
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import org.spongepowered.asm.service.IClassBytecodeProvider
import org.spongepowered.asm.service.IClassProvider
import org.spongepowered.asm.service.IClassTracker
import org.spongepowered.asm.service.IMixinAuditTrail
import org.spongepowered.asm.service.ITransformerProvider
import org.spongepowered.asm.service.MixinServiceAbstract
import org.spongepowered.asm.util.IConsumer
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.InputStream
import java.net.URL

class AlaphantMixinService : MixinServiceAbstract() {
    private val classProvider = AgentClassProvider()
    private val bytecodeProvider = AgentBytecodeProvider()

    private var phaseConsumer: IConsumer<MixinEnvironment.Phase>? = null

    override fun getName(): String = "Alaphant/Agent"

    override fun isValid(): Boolean = true

    override fun getClassProvider(): IClassProvider = classProvider

    override fun getBytecodeProvider(): IClassBytecodeProvider = bytecodeProvider

    override fun getTransformerProvider(): ITransformerProvider? = null

    override fun getClassTracker(): IClassTracker? = null

    override fun getAuditTrail(): IMixinAuditTrail? = null

    override fun getPlatformAgents(): Collection<String> = emptyList()

    override fun getPrimaryContainer(): IContainerHandle = ContainerHandleVirtual("alaphant")

    override fun getMixinContainers(): Collection<IContainerHandle> = emptyList()

    override fun getResourceAsStream(name: String): InputStream? =
        javaClass.classLoader.getResourceAsStream(name)

    @Suppress("DEPRECATION")
    override fun wire(phase: MixinEnvironment.Phase, phaseConsumer: IConsumer<MixinEnvironment.Phase>) {
        super.wire(phase, phaseConsumer)
        this.phaseConsumer = phaseConsumer
    }

    @Suppress("DEPRECATION")
    override fun unwire() {
        super.unwire()
        phaseConsumer = null
    }

    fun gotoPhase(phase: MixinEnvironment.Phase) {
        val consumer = phaseConsumer
            ?: error("Mixin has not offered a phase consumer yet; MixinBootstrap.init() must run first")
        consumer.accept(phase)
    }

    fun createTransformer(): IMixinTransformer =
        getInternal(IMixinTransformerFactory::class.java).createTransformer()

    private class AgentClassProvider : IClassProvider {
        private val loader: ClassLoader = AlaphantMixinService::class.java.classLoader

        @Deprecated("Legacy API", ReplaceWith("emptyArray()"))
        override fun getClassPath(): Array<URL> = emptyArray()

        override fun findClass(name: String): Class<*> = Class.forName(name, false, loader)

        override fun findClass(name: String, initialize: Boolean): Class<*> =
            Class.forName(name, initialize, loader)

        override fun findAgentClass(name: String, initialize: Boolean): Class<*> =
            Class.forName(name, initialize, loader)
    }

    private class AgentBytecodeProvider : IClassBytecodeProvider {
        private val loader: ClassLoader = AlaphantMixinService::class.java.classLoader

        override fun getClassNode(name: String): ClassNode = getClassNode(name, false, 0)

        override fun getClassNode(name: String, runTransformers: Boolean): ClassNode =
            getClassNode(name, runTransformers, 0)

        override fun getClassNode(name: String, runTransformers: Boolean, readerFlags: Int): ClassNode {
            val resource = name.replace('.', '/') + ".class"
            val bytes = loader.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: throw ClassNotFoundException(name)
            return ClassNode().also { ClassReader(bytes).accept(it, readerFlags) }
        }
    }
}
