package charles.alaphant.mixin.gui.settings;

import com.xk72.charles.gui.settings.HighlightSettingsPanel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;

@Mixin(HighlightSettingsPanel.class)
public abstract class MixinHighlightSettingsPanel {
    private static @Shadow @Final @Mutable Color[] field_1233;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void clinit(CallbackInfo ci) {
        MixinHighlightSettingsPanel.field_1233 = new Color[]{
                null,
                new Color(11789005),
                new Color(16633260),
                new Color(13358568),
                new Color(16042724),
                new Color(15136201),
                new Color(16773806),
                new Color(15852236),
                new Color(13421772),
                new Color(229, 111, 96)
        };
    }
}
