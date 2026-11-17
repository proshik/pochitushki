package ru.proshik.pochitushki.service

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.configuration.properties.JwtProperties
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.spec.SecretKeySpec

@Service
@EnableConfigurationProperties(JwtProperties::class)
class JwtService(private val jwtProperties: JwtProperties) {

    // Validate eagerly at startup (fail fast) rather than on first token use.
    private val signingKey: SecretKeySpec = run {
        val keyBytes = try {
            Base64.getDecoder().decode(jwtProperties.secret)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "jwt.secret must be valid base64 (set the JWT_SECRET env var to a >=32-byte base64 value)", e
            )
        }
        require(keyBytes.size >= 32) {
            "jwt.secret must decode to at least 32 bytes for HS256 (got ${keyBytes.size})"
        }
        SecretKeySpec(keyBytes, "HmacSHA256")
    }

    /**
     * @param miniApp marks a session opened inside Telegram (claim `tma`). It is what loosens
     * the CSP for telegram.org and Telegram Web framing (SecurityHeadersFilter), so it rides in
     * the signed token rather than in a cookie of its own that anyone could set.
     */
    fun createToken(userId: Long, miniApp: Boolean = false): String {
        val now = Date()
        val expiry = Date(now.time + java.util.concurrent.TimeUnit.DAYS.toMillis(jwtProperties.ttlDays))
        val builder = Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(expiry)
        if (miniApp) builder.claim(MINI_APP_CLAIM, true)
        return builder.signWith(signingKey).compact()
    }

    fun extractUserId(token: String): Long? = parseToken(token)?.userId

    /**
     * The claims the interceptor needs: who, and when the token was issued. `issuedAt` is what
     * makes logout able to revoke — it is compared against users.tokens_valid_after.
     */
    fun parseToken(token: String): TokenInfo? = try {
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload
        TokenInfo(
            userId = claims.subject.toLong(),
            issuedAt = claims.issuedAt?.toInstant(),
            miniApp = claims[MINI_APP_CLAIM] == true,
        )
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    data class TokenInfo(val userId: Long, val issuedAt: Instant?, val miniApp: Boolean = false)

    companion object {
        const val MINI_APP_CLAIM = "tma"
    }
}
