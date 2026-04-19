package ru.proshik.pochitushki.service

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import ru.proshik.pochitushki.configuration.properties.TelegramOAuthProperties
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class TelegramUserInfo(
    val id: Long,
    @JsonProperty("first_name") val firstName: String,
    val username: String?,
    @JsonProperty("photo_url") val photoUrl: String?,
)

data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String,
)

@FeignClient(name = "telegram-oidc", url = "\${telegram.oauth.base-url}")
interface TelegramOidcClient {
    @PostMapping("/token", consumes = ["application/x-www-form-urlencoded"])
    fun exchangeCode(@RequestBody params: LinkedMultiValueMap<String, String>): TokenResponse

    @GetMapping("/userinfo")
    fun getUserInfo(@RequestHeader("Authorization") bearerToken: String): TelegramUserInfo
}

@Service
@EnableConfigurationProperties(TelegramOAuthProperties::class)
class TelegramOidcService(
    private val client: TelegramOidcClient,
    private val props: TelegramOAuthProperties,
) {
    fun buildAuthorizationUrl(state: String, codeChallenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
        return "${props.baseUrl}/auth" +
            "?client_id=${enc(props.clientId)}" +
            "&redirect_uri=${enc(props.redirectUri)}" +
            "&response_type=code" +
            "&scope=openid" +
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
        return client.getUserInfo("Bearer ${tokenResponse.accessToken}")
    }
}
