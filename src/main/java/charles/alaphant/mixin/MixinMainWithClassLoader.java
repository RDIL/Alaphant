package charles.alaphant.mixin;

import com.xk72.charles.gui.MainWithClassLoader;
import com.xk72.lib.a;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainWithClassLoader.class)
public class MixinMainWithClassLoader {
    /**
     * @author rdil
     */
    @Overwrite
    public static void main(String[] args) {
        a.a("com.xk72.charles.gui.Main", args, Thread.currentThread().getContextClassLoader());
    }
}
