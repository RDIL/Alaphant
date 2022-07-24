package alaphant.mixin.util;

import com.xk72.util.lexar.CssSyntaxKit;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(CssSyntaxKit.class)
public class MixinCssSyntaxKit {
    /**
     * Repair reflection under remapped environment.
     */
    @Contract(pure = true)
    @ModifyArg(method = "createLexer", at = @At(value = "INVOKE", target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"))
    private static @NotNull String getCssLexerClassName(String className) {
        return "com.xk72.util.lexar.CssLexer";
    }
}
