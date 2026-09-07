package ru.proshik.pochitushki.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import ru.proshik.pochitushki.BaseIntegrationTest

/**
 * Local development over plain http needs cookies without the Secure attribute.
 *
 * Chrome and Firefox accept a Secure cookie from `http://localhost` because they
 * reuse the Secure Contexts notion of a trustworthy origin for cookies as well.
 * WebKit never wired the two together, so Safari drops the cookie without a word
 * — and the failure surfaces as `/login?error=state`, pointing at the wrong
 * thing entirely. No browser accepts it from `http://192.168.x.x`, which is what
 * breaks testing from a phone.
 *
 * The default stays true, so production cannot be degraded by forgetting a
 * variable; this test pins the escape hatch that makes it switchable.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.security.cookie.secure=false"])
class AuthControllerInsecureCookieTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `oidc cookies drop the Secure attribute when cookie secure is off`() {
        val result = mockMvc.perform(get("/auth/telegram")).andReturn()

        val setCookies = result.response.getHeaders("Set-Cookie")
            .filter { it.startsWith("oidc_state=") || it.startsWith("oidc_code_verifier=") }

        assertTrue(setCookies.size == 2, "ожидались обе oidc-куки: $setCookies")
        setCookies.forEach {
            assertFalse(it.contains("Secure"), "кука не должна быть Secure при COOKIE_SECURE=false: $it")
            // Everything else about the cookie stays as it was: turning Secure off
            // is not a licence to loosen the rest.
            assertTrue(it.contains("HttpOnly"), "HttpOnly должен остаться: $it")
            assertTrue(it.contains("SameSite=Lax"), "SameSite=Lax должен остаться: $it")
        }
    }
}
