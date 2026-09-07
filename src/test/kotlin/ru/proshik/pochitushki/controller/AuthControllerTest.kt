package ru.proshik.pochitushki.controller

import com.github.tomakehurst.wiremock.client.WireMock.*
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import ru.proshik.pochitushki.BaseIntegrationTest
import java.time.Instant
import java.util.Base64

@AutoConfigureMockMvc
class AuthControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
        wireMockOidc.resetAll()
    }

    @Test
    fun `GET login returns 200 with login page`() {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Войти через Telegram")))
    }

    @Test
    fun `responses carry baseline security headers`() {
        val result = mockMvc.perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andReturn()

        val csp = result.response.getHeader("Content-Security-Policy") ?: ""

        assertTrue(csp.contains("default-src 'self'"), "CSP should default to same-origin: $csp")
        assertTrue(csp.contains("frame-ancestors 'none'"), "CSP should forbid framing: $csp")

        // The point of the policy: scripts run only from this origin. Reintroducing
        // 'unsafe-inline' or 'unsafe-eval' for scripts would silently gut the XSS
        // defence, so guard it here rather than trusting review. See app.js for the
        // template rules that keep this achievable.
        assertTrue(csp.contains("script-src 'self'"), "CSP should pin scripts to 'self': $csp")
        val scriptSrc = csp.split(";").first { it.trim().startsWith("script-src") }
        assertFalse(scriptSrc.contains("unsafe-inline"), "script-src must not allow inline: $csp")
        assertFalse(csp.contains("unsafe-eval"), "CSP must not allow eval anywhere: $csp")

        // Cover photos are proxied by OgImageProxyService, so no third-party image
        // origin is needed. Reopening img-src to https: would put the reader's IP
        // back on every site they saved — guard it the same way as script-src.
        val imgSrc = csp.split(";").first { it.trim().startsWith("img-src") }
        assertEquals("img-src 'self'", imgSrc.trim(), "img-src must stay on this origin: $csp")
    }

    @Test
    fun `GET auth-telegram redirects to Telegram OIDC and sets state + verifier cookies`() {
        val result = mockMvc.perform(get("/auth/telegram"))
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val location = result.response.getHeader("Location") ?: ""
        assertTrue(location.contains("response_type=code"), "Should redirect with code flow")
        assertTrue(location.contains("code_challenge"), "Should include PKCE challenge")

        val cookies = result.response.cookies
        assertNotNull(cookies.firstOrNull { it.name == "oidc_state" }, "Should set oidc_state cookie")
        assertNotNull(cookies.firstOrNull { it.name == "oidc_code_verifier" }, "Should set code_verifier cookie")
    }

    @Test
    fun `GET callback with valid code creates new user and sets JWT cookie`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "99999", name = "Test", username = "testuser")))

        val initResult = mockMvc.perform(get("/auth/telegram")).andReturn()
        val stateCookie = initResult.response.cookies.first { it.name == "oidc_state" }
        val verifierCookie = initResult.response.cookies.first { it.name == "oidc_code_verifier" }

        val callbackResult = mockMvc.perform(
            get("/auth/telegram/callback")
                .param("code", "test-code")
                .param("state", stateCookie.value)
                .cookie(stateCookie)
                .cookie(verifierCookie)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/"))
            .andReturn()

        val authCookie = callbackResult.response.cookies.firstOrNull { it.name == "auth_token" }
        assertNotNull(authCookie, "auth_token cookie должен быть установлен")
        assertNotNull(jwtService.extractUserId(authCookie!!.value), "JWT должен быть валидным")

        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE telegram_id = 99999", Int::class.java
        )
        assertEquals(1, count, "Новый пользователь должен быть создан в БД")
    }

    @Test
    fun `GET callback sets auth_token cookie with SameSite Lax, HttpOnly and Secure`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "99999", name = "Test", username = "testuser")))

        val initResult = mockMvc.perform(get("/auth/telegram")).andReturn()
        val stateCookie = initResult.response.cookies.first { it.name == "oidc_state" }
        val verifierCookie = initResult.response.cookies.first { it.name == "oidc_code_verifier" }

        val callbackResult = mockMvc.perform(
            get("/auth/telegram/callback")
                .param("code", "test-code")
                .param("state", stateCookie.value)
                .cookie(stateCookie)
                .cookie(verifierCookie)
        ).andReturn()

        val authSetCookie = callbackResult.response.getHeaders("Set-Cookie")
            .first { it.startsWith("auth_token=") }
        assertTrue(authSetCookie.contains("SameSite=Lax"), "auth_token должен иметь SameSite=Lax: $authSetCookie")
        assertTrue(authSetCookie.contains("HttpOnly"), "auth_token должен быть HttpOnly: $authSetCookie")
        assertTrue(authSetCookie.contains("Secure"), "auth_token должен быть Secure: $authSetCookie")
    }

    @Test
    fun `GET callback with wrong state redirects to login with error=state`() {
        val initResult = mockMvc.perform(get("/auth/telegram")).andReturn()
        val verifierCookie = initResult.response.cookies.first { it.name == "oidc_code_verifier" }

        mockMvc.perform(
            get("/auth/telegram/callback")
                .param("code", "test-code")
                .param("state", "wrong-state")
                .cookie(Cookie("oidc_state", "correct-state"))
                .cookie(verifierCookie)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login?error=state"))
    }

    @Test
    fun `GET callback for existing user does not duplicate in DB`() {
        jdbcTemplate.execute(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (88888, 'existing', 'Existing', null,
                       '{"languageCode":"ru","tgFeedEntriesNumber":5}'::jsonb)"""
        )

        stubTokenWithIdToken(fakeIdToken(validClaims(id = "88888", name = "Existing", username = "existing")))

        val initResult = mockMvc.perform(get("/auth/telegram")).andReturn()
        val stateCookie = initResult.response.cookies.first { it.name == "oidc_state" }
        val verifierCookie = initResult.response.cookies.first { it.name == "oidc_code_verifier" }

        mockMvc.perform(
            get("/auth/telegram/callback")
                .param("code", "test-code")
                .param("state", stateCookie.value)
                .cookie(stateCookie)
                .cookie(verifierCookie)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/"))

        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE telegram_id = 88888", Int::class.java
        )
        assertEquals(1, count, "Существующий пользователь не должен дублироваться")
    }

    @Test
    fun `GET callback rejects expired id_token`() {
        val past = Instant.now().epochSecond - 3600
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55501", exp = past)))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55501))
    }

    @Test
    fun `GET callback rejects id_token with wrong aud`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55502", aud = "\"someone-else\"")))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55502))
    }

    @Test
    fun `GET callback accepts id_token with valid exp and aud`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55503")))

        val (location, cookie) = runCallback()

        assertEquals("/", location)
        assertNotNull(cookie)
        assertEquals(1, userCount(55503))
    }

    // --- Phase 2: the claim checks become mandatory ---

    @Test
    fun `GET callback rejects id_token without iss`() {
        // Enforcing a claim only when present hands the decision to whoever issued
        // the token. The signature is not verified here, so these checks carry the
        // whole weight.
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55510", iss = null)))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55510))
    }

    @Test
    fun `GET callback rejects id_token without aud`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55511", aud = null)))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55511))
    }

    @Test
    fun `GET callback rejects id_token without exp`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55512", exp = null)))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55512))
    }

    @Test
    fun `GET callback rejects an issuer that merely contains telegram`() {
        // The old rule was `iss contains "telegram"`, which any host carrying that
        // substring satisfies. Exact equality is what makes swapping the provider a
        // deliberate act rather than an accident.
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55513", iss = "https://telegram-auth.example.com")))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55513))
    }

    @Test
    fun `GET callback rejects an issuer differing only by a trailing slash`() {
        // OIDC Core 3.1.3.7 asks for exact equality; normalising a slash away turns
        // the check into an approximation.
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55514", iss = "http://localhost:${wireMockOidc.port()}/")))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55514))
    }

    @Test
    fun `GET callback rejects an audience array that does not list us`() {
        // As `claims["aud"] as? String` an array yields null and the audience check
        // switches itself off without a word.
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55515", aud = """["someone-else","another"]""")))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55515))
    }

    @Test
    fun `GET callback accepts an audience array that lists us`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55516", aud = """["someone-else","test-client-id"]""")))

        val (location, cookie) = runCallback()

        assertEquals("/", location)
        assertNotNull(cookie)
        assertEquals(1, userCount(55516))
    }

    @Test
    fun `GET callback rejects an id_token issued in the future`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55517", iat = Instant.now().epochSecond + 300)))

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(55517))
    }

    @Test
    fun `GET callback tolerates small clock skew on iat`() {
        stubTokenWithIdToken(fakeIdToken(validClaims(id = "55518", iat = Instant.now().epochSecond + 30)))

        val (location, cookie) = runCallback()

        assertEquals("/", location)
        assertNotNull(cookie)
        assertEquals(1, userCount(55518))
    }

    @Test
    fun `token call does not follow redirects`() {
        // Feign follows redirects by default, which would have the id_token read
        // from wherever the last hop pointed. There is no legitimate redirect on a
        // token endpoint.
        wireMockOidc.stubFor(
            post(urlEqualTo("/token")).willReturn(
                aResponse().withStatus(302).withHeader("Location", "/token-elsewhere")
            )
        )
        wireMockOidc.stubFor(
            get(urlEqualTo("/token-elsewhere")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"access_token":"t","token_type":"Bearer","id_token":"${fakeIdToken(validClaims(id = "66666"))}"}""")
            )
        )

        val (location, _) = runCallback()

        assertEquals("/login?error=server", location)
        assertEquals(0, userCount(66666), "the identity from behind the redirect must never be used")
    }

    @Test
    fun `GET callback clears the oidc cookies even when state does not match`() {
        // A rejected attempt used to leave oidc_state and oidc_code_verifier valid
        // for their full 300 seconds. code_verifier is half of the PKCE pair, and
        // it has no business surviving an attempt already judged suspicious.
        val initResult = mockMvc.perform(get("/auth/telegram")).andReturn()
        val verifierCookie = initResult.response.cookies.first { it.name == "oidc_code_verifier" }

        val result = mockMvc.perform(
            get("/auth/telegram/callback")
                .param("code", "test-code")
                .param("state", "wrong-state")
                .cookie(Cookie("oidc_state", "correct-state"))
                .cookie(verifierCookie)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login?error=state"))
            .andReturn()

        for (name in listOf("oidc_state", "oidc_code_verifier")) {
            val cookie = result.response.cookies.firstOrNull { it.name == name }
            assertNotNull(cookie, "$name должен быть очищен на неудачной попытке")
            assertEquals(0, cookie!!.maxAge, "$name должен быть удалён, а не оставлен жить")
        }
    }

    @Test
    fun `GET logout clears auth cookie and redirects to login`() {
        val result = mockMvc.perform(get("/logout").with(withAuth(1L)))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login"))
            .andReturn()

        val authCookie = result.response.cookies.firstOrNull { it.name == "auth_token" }
        assertTrue(authCookie == null || authCookie.maxAge == 0, "auth_token cookie должен быть удалён")
    }

    // --- Helpers for id_token claim-validation tests ---

    /**
     * A complete, well-formed claim set. Every strictness test below starts from
     * it and breaks exactly one thing, so a failure names its own cause.
     */
    private fun validClaims(
        id: String = "55500",
        name: String = "T",
        username: String = "t",
        iss: String? = "http://localhost:${wireMockOidc.port()}",
        aud: String? = "\"test-client-id\"",
        exp: Long? = Instant.now().epochSecond + 3600,
        iat: Long? = Instant.now().epochSecond,
    ): String {
        val fields = mutableListOf(
            """"sub":"42220"""",
            """"id":"$id"""",
            """"name":"$name"""",
            """"preferred_username":"$username"""",
        )
        iss?.let { fields += """"iss":"$it"""" }
        aud?.let { fields += """"aud":$it""" }
        exp?.let { fields += """"exp":$it""" }
        iat?.let { fields += """"iat":$it""" }
        return "{${fields.joinToString(",")}}"
    }

    private fun fakeIdToken(payloadJson: String): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return "${b64("""{"alg":"RS256","typ":"JWT"}""")}.${b64(payloadJson)}.fakesig"
    }

    private fun stubTokenWithIdToken(idToken: String) {
        wireMockOidc.stubFor(
            post(urlEqualTo("/token")).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"access_token":"tok","token_type":"Bearer","id_token":"$idToken"}""")
            )
        )
    }

    /** Drives the full OIDC init + callback and returns (redirect location, auth_token value?). */
    private fun runCallback(): Pair<String?, String?> {
        val init = mockMvc.perform(get("/auth/telegram")).andReturn()
        val state = init.response.cookies.first { it.name == "oidc_state" }
        val verifier = init.response.cookies.first { it.name == "oidc_code_verifier" }

        val result = mockMvc.perform(
            get("/auth/telegram/callback")
                .param("code", "test-code")
                .param("state", state.value)
                .cookie(state)
                .cookie(verifier)
        ).andReturn()

        return result.response.getHeader("Location") to
            result.response.cookies.firstOrNull { it.name == "auth_token" }?.value
    }

    private fun userCount(telegramId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE telegram_id = ?", Int::class.java, telegramId)!!
}
