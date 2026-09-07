package ru.proshik.pochitushki.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import ru.proshik.pochitushki.configuration.properties.TelegramOAuthProperties
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class TelegramUserInfo(
    val id: Long,
    val firstName: String?,
    val username: String?,
    val photoUrl: String?,
)

data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("id_token") val idToken: String?,
)

@FeignClient(name = "telegram-oidc", url = "\${telegram.oauth.base-url}")
interface TelegramOidcClient {
    @PostMapping("/token", consumes = ["application/x-www-form-urlencoded"])
    fun exchangeCode(@RequestBody params: LinkedMultiValueMap<String, String>): TokenResponse
}

@Service
@EnableConfigurationProperties(TelegramOAuthProperties::class)
class TelegramOidcService(
    private val client: TelegramOidcClient,
    private val props: TelegramOAuthProperties,
    private val objectMapper: ObjectMapper,
) {
    fun buildAuthorizationUrl(state: String, codeChallenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
        return "${props.baseUrl}/auth" +
            "?client_id=${enc(props.clientId)}" +
            "&redirect_uri=${enc(props.redirectUri)}" +
            "&response_type=code" +
            "&scope=${enc("openid profile")}" +
            "&state=${enc(state)}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256"
    }

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun computeCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun exchangeCode(code: String, codeVerifier: String): TelegramUserInfo {
        val params = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", props.redirectUri)
            add("client_id", props.clientId)
            add("client_secret", props.clientSecret)
            add("code_verifier", codeVerifier)
        }
        val tokenResponse = client.exchangeCode(params)

        val rawIdToken = tokenResponse.idToken
            ?: error("Telegram token response is missing id_token")
        val parts = rawIdToken.split(".")
        require(parts.size == 3) { "Unexpected id_token format: expected 3 segments, got ${parts.size}" }

        // The signature is intentionally not verified: the token comes straight
        // from the token endpoint over TLS in a server-to-server call, so the
        // transport already guarantees integrity. Telegram does publish a JWKS at
        // /.well-known/jwks.json, so verifying is possible and would be the
        // stronger answer; not doing it is a decision, not an absence of means.
        //
        // That makes the claim checks below load-bearing rather than defence in
        // depth, and every one of them is mandatory: enforcing a claim "only when
        // present" hands the decision to whoever issued the token.
        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))
        @Suppress("UNCHECKED_CAST")
        val claims = objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any?>

        val iss = claims["iss"] as? String
        requireNotNull(iss) { "id_token has no iss claim" }
        // Exact equality, as OIDC Core 3.1.3.7 requires: a trailing slash makes a
        // different issuer identifier, and normalising it away turns the check
        // into an approximation.
        require(iss == props.expectedIssuer) { "id_token iss is $iss, expected ${props.expectedIssuer}" }

        checkAudience(claims["aud"])

        val now = Instant.now().epochSecond
        val exp = claimAsEpochSecond(claims["exp"], "exp")
        require(exp > now) { "id_token has expired" }
        // iat is not sent by every provider, but a token minted in the future
        // means something is wrong with one of the two clocks — or with the token.
        claims["iat"]?.let {
            val iat = claimAsEpochSecond(it, "iat")
            require(iat <= now + CLOCK_SKEW_SECONDS) { "id_token iat is $iat, which is in the future" }
        }

        return TelegramUserInfo(
            id = (claims["id"] as? Number)?.toLong()
                ?: claims["id"]?.toString()?.toLongOrNull()
                ?: error("Missing or invalid 'id' claim in id_token"),
            firstName = claims["name"] as? String,
            username = claims["preferred_username"] as? String,
            photoUrl = claims["picture"] as? String,
        )
    }

    /**
     * Accepts `aud` both as a string and as an array, which OIDC allows. Read
     * only as a string, an array matches nothing — and a check that matches
     * nothing is a check that is switched off.
     */
    private fun checkAudience(aud: Any?) {
        when (aud) {
            is String -> require(aud == props.clientId) {
                "id_token aud is $aud, expected ${props.clientId}"
            }
            is List<*> -> require(aud.any { it == props.clientId }) {
                "id_token aud $aud does not contain ${props.clientId}"
            }
            null -> error("id_token has no aud claim")
            else -> error("id_token aud is ${aud::class.simpleName}, expected a string or a list of strings")
        }
    }

    private fun claimAsEpochSecond(value: Any?, name: String): Long =
        when (value) {
            is Number -> value.toLong()
            null -> error("id_token has no $name claim")
            else -> error("id_token $name is ${value::class.simpleName}, expected a number")
        }

    private companion object {
        /** How far ahead of us the issuer's clock may be. */
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
