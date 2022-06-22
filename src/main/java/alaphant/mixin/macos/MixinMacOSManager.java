package alaphant.mixin.macos;

import com.xk72.charles.macos.MacOSManager;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(MacOSManager.class)
public class MixinMacOSManager {
    /**
     * Repair reflection under remapped environment.
     */
    @Contract(pure = true)
    @ModifyArg(method = "isMacOS", at = @At(value = "INVOKE", target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"))
    private static @NotNull String classForName(String clazz) {
        return "com.xk72.charles.macos.MacOSImpl";
    }

    /**
     * Repair reflection under remapped environment.
     */
    @Contract(pure = true)
    @ModifyArg(method = "getMacOSImpl", at = @At(value = "INVOKE", target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"), slice = @Slice(from = @At(value = "INVOKE", target = "Ljava/util/logging/Logger;log(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Throwable;)V")))
    private static @NotNull String classForName2(String clazz) {
        return "com.xk72.charles.macos.MacOSImpl";
    }

    /**
     * Repair reflection under remapped environment.
     */
    @Contract(pure = true)
    @ModifyArg(method = "getMacOSImpl", at = @At(value = "INVOKE", target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"), slice = @Slice(to = @At(value = "INVOKE", target = "Ljava/util/logging/Logger;log(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Throwable;)V")))
    private static @NotNull String classForName3(String clazz) {
        return "com.xk72.charles.macos.gui.MacOSGUIImpl";
    }
}
