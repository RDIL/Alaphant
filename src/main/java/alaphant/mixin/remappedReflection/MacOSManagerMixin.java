package alaphant.mixin.remappedReflection;

import com.charlesproxy.macos.MacOSImpl;
import com.charlesproxy.macos.MacOSManager;
import com.charlesproxy.macos.gui.MacOSGUIImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MacOSManager.class)
public abstract class MacOSManagerMixin {
    @ModifyArg(
            method = "isMacOS",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"
            ),
            index = 0
    )
    private static String alaphant$remapAvailabilityProbe(final String official) {
        return alaphant$toNamedClass(official);
    }

    @ModifyArg(
            method = "getMacOSImpl",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"
            ),
            index = 0
    )
    private static String alaphant$remapImplementationLookup(final String official) {
        return alaphant$toNamedClass(official);
    }

    @Unique
    private static String alaphant$toNamedClass(final String official) {
        if ("com.charlesproxy.macos.UOUV".equals(official)) {
            return MacOSImpl.class.getName();
        }
        if ("com.charlesproxy.macos.sdfE.oaSX".equals(official)) {
            return MacOSGUIImpl.class.getName();
        }
        return official;
    }
}
