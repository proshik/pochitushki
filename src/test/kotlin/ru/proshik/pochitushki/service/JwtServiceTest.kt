package ru.proshik.pochitushki.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.proshik.pochitushki.BaseIntegrationTest

class JwtServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var jwtService: JwtService

    @Test
    fun `createToken returns non-blank string`() {
        assertTrue(jwtService.createToken(42L).isNotBlank())
    }

    @Test
    fun `extractUserId returns correct userId from valid token`() {
        val token = jwtService.createToken(42L)
        assertEquals(42L, jwtService.extractUserId(token))
    }

    @Test
    fun `extractUserId returns null for garbage input`() {
        assertNull(jwtService.extractUserId("not.a.jwt.token"))
    }

    @Test
    fun `extractUserId returns null for token with wrong signature`() {
        val token = jwtService.createToken(1L)
        val tampered = token.dropLast(5) + "XXXXX"
        assertNull(jwtService.extractUserId(tampered))
    }
}
