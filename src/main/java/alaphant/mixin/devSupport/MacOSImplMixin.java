package alaphant.mixin.devSupport;

import com.charlesproxy.macos.MacOSImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MacOSImpl.class)
public abstract class MacOSImplMixin {
    /**
     * In dev, charles is executing against java, not the signed charles bundle, and thus the system
     * proxy helper won't work. We'll just early abort it if that's the case to avoid the popup spam.
     */
    @Inject(method = "testProxyHelper()Z", at = @At("HEAD"), cancellable = true)
    private static void alaphant$skipUnusableProxyHelper(final CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.getBoolean("alaphant.macosProxyHelper")) {
            cir.setReturnValue(false);
        }
    }
}
