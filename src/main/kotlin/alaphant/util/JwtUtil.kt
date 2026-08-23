package alaphant.util

import com.auth0.jwt.JWT
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.interfaces.DecodedJWT
import org.jetbrains.annotations.Contract

object JwtUtil {
    private const val HEADER = "eyJhb"

    @JvmStatic
    @Contract(pure = true)
    fun getJwtFromBearerValue(token: String): DecodedJWT? {
        if (!token.startsWith(HEADER)) {
            return null
        }

        try {
            return JWT.decode(token)
        } catch (e: JWTDecodeException) {
            e.printStackTrace()
            return null
        }
    }
}
