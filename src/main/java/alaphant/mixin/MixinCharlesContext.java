package alaphant.mixin;

import com.xk72.charles.CharlesContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.swing.*;
import java.util.logging.Logger;

@Mixin(CharlesContext.class)
public class MixinCharlesContext {
    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "start", at = @At(value = "INVOKE", target = "Lcom/xk72/charles/CharlesContext;addShutdownHook()V", shift = At.Shift.BEFORE))
    private void start(CallbackInfo ci) {
        LOGGER.info("Active look and feel: " + UIManager.getLookAndFeel().getClass().getName());
    }
}
