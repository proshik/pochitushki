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
        // TelegramOidcClient uses @PostMapping("/token"), so stub at /token (not /auth/token)
        wireMockOidc.stubFor(
            post(urlEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"access_token":"test-tok","token_type":"Bearer"}"""))
        )
        wireMockOidc.stubFor(
            get(urlEqualTo("/userinfo"))
                .withHeader("Authorization", equalTo("Bearer test-tok"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"id":99999,"first_name":"Test","username":"testuser","photo_url":null}"""))
        )

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

        // TelegramOidcClient uses @PostMapping("/token"), so stub at /token (not /auth/token)
        wireMockOidc.stubFor(
            post(urlEqualTo("/token"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"access_token":"tok2","token_type":"Bearer"}"""))
        )
        wireMockOidc.stubFor(
            get(urlEqualTo("/userinfo"))
                .willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .withBody("""{"id":88888,"first_name":"Existing","username":"existing","photo_url":null}"""))
        )

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
    fun `GET logout clears auth cookie and redirects to login`() {
        val result = mockMvc.perform(get("/logout").with(withAuth(1L)))
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", "/login"))
            .andReturn()

        val authCookie = result.response.cookies.firstOrNull { it.name == "auth_token" }
        assertTrue(authCookie == null || authCookie.maxAge == 0, "auth_token cookie должен быть удалён")
    }
}
