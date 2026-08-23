package alaphant.agent

import org.spongepowered.asm.service.IMixinServiceBootstrap

class AlaphantMixinServiceBootstrap : IMixinServiceBootstrap {
    override fun getName(): String = "Alaphant"

    override fun getServiceClassName(): String = "alaphant.agent.AlaphantMixinService"

    override fun bootstrap() = Unit
}
