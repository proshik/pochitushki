package ru.proshik.pochitushki.service

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.configuration.properties.JwtProperties
import java.util.Base64
import java.util.Date
import javax.crypto.spec.SecretKeySpec

@Service
@EnableConfigurationProperties(JwtProperties::class)
class JwtService(private val jwtProperties: JwtProperties) {

    private val signingKey by lazy {
        val keyBytes = Base64.getDecoder().decode(jwtProperties.secret)
        SecretKeySpec(keyBytes, "HmacSHA256")
    }

    fun createToken(userId: Long): String {
        val now = Date()
        val expiry = Date(now.time + java.util.concurrent.TimeUnit.DAYS.toMillis(jwtProperties.ttlDays))
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact()
    }

    fun extractUserId(token: String): Long? = try {
        Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload
            .subject
            .toLong()
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}
