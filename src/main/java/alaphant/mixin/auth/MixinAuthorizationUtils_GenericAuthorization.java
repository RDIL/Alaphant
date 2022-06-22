package alaphant.mixin.auth;

import alaphant.util.JwtUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.xk72.charles.lib.AuthorizationUtils_GenericAuthorization;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AuthorizationUtils_GenericAuthorization.class)
public abstract class MixinAuthorizationUtils_GenericAuthorization {
    @Shadow
    public abstract void addParameter(String name, String value);

    public void addParam(@Nullable String name, @Nullable String value) {
        if (name == null || value == null) {
            return;
        }

        addParameter(name, value);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    public void ctorTail(String method, String token, CallbackInfo ci) {
        if (token == null) {
            return;
        }

        DecodedJWT jwt = JwtUtil.getJwtFromBearerValue(token);

        if (jwt == null) {
            return;
        }

        this.addParam("JSON Web Token", "True");
        this.addParam("Token", jwt.getToken());
        this.addParam("Type", jwt.getType());
        this.addParam("Algorithm", jwt.getAlgorithm());
        this.addParam("Issuer", jwt.getIssuer());
        this.addParam("Subject", jwt.getSubject());
        this.addParam("Expires At", jwt.getExpiresAt().toString());
        this.addParam("Not Before", jwt.getNotBefore().toString());
        this.addParam("Audience", String.join(", ", jwt.getAudience()));
    }
}
