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
        require(parts.size >= 3) { "Unexpected id_token format: expected 3 segments, got ${parts.size}" }

        // Signature verification is intentionally skipped: the token is received directly from
        // Telegram's token endpoint over TLS (server-to-server), so the transport guarantees integrity.
        val payloadJson = String(Base64.getUrlDecoder().decode(parts[1]))
        @Suppress("UNCHECKED_CAST")
        val claims = objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any?>

        // Defense-in-depth claim checks (signature is not verified — see note above). Each claim
        // is enforced only when present, since this nonstandard Telegram OIDC may omit some.
        val now = java.time.Instant.now().epochSecond
        (claims["exp"] as? Number)?.let {
            require(it.toLong() > now) { "id_token has expired" }
        }
        (claims["aud"] as? String)?.let {
            require(it == props.clientId) { "id_token aud mismatch (expected ${props.clientId})" }
        }
        (claims["iss"] as? String)?.let {
            require(it.contains("telegram", ignoreCase = true)) { "id_token iss mismatch: $it" }
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
}
