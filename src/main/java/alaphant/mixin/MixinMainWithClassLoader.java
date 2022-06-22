package alaphant.mixin;

import com.xk72.charles.gui.MainWithClassLoader;
import com.xk72.lib.ReflectionUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainWithClassLoader.class)
public class MixinMainWithClassLoader {
    @Overwrite
    public static void main(String[] args) {
        ReflectionUtil.callMainWithClassLoader("com.xk72.charles.gui.Main", args, Thread.currentThread().getContextClassLoader());
    }
}
