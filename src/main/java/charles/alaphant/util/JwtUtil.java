package charles.alaphant.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JwtUtil {
    private static final String HEADER = "eyJhb";

    @Contract(pure = true)
    @Nullable
    public static DecodedJWT getJwtFromBearerValue(final @NotNull String token) {
        if (!token.startsWith(HEADER)) {
            return null;
        }

        try {
            return JWT.decode(token);
        } catch (JWTDecodeException e) {
            e.printStackTrace();
            return null;
        }
    }
}
