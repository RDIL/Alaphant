package alaphant.mixin.jwt;

import alaphant.util.JwtUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.charlesproxy.lib.AuthorizationUtils$GenericAuthorization;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AuthorizationUtils$GenericAuthorization.class)
public abstract class MixinAuthorizationUtils$GenericAuthorization {
    @Shadow
    public abstract void addParameter(String name, String value);

    @Unique
    public void alaphant$addParam(@Nullable String name, @Nullable String value) {
        if (name == null || value == null) {
            return;
        }

        addParameter(name, value);
    }

    @Inject(method = "<init>*", at = @At("TAIL"))
    public void ctorTail(String method, String token, CallbackInfo ci) {
        if (token == null) {
            return;
        }

        DecodedJWT jwt = JwtUtil.getJwtFromBearerValue(token);

        if (jwt == null) {
            return;
        }

        this.alaphant$addParam("JSON Web Token", "True");
        this.alaphant$addParam("Token", jwt.getToken());
        this.alaphant$addParam("Type", jwt.getType());
        this.alaphant$addParam("Algorithm", jwt.getAlgorithm());
        this.alaphant$addParam("Issuer", jwt.getIssuer());
        this.alaphant$addParam("Subject", jwt.getSubject());
        this.alaphant$addParam("Expires At", jwt.getExpiresAt().toString());
        this.alaphant$addParam("Not Before", jwt.getNotBefore().toString());
        this.alaphant$addParam("Audience", String.join(", ", jwt.getAudience()));
    }
}
