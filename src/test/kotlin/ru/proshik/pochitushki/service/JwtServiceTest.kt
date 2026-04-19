package ru.proshik.pochitushki.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.configuration.properties.JwtProperties

class JwtServiceTest {

    private val props = JwtProperties(
        secret = "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktMzI=",
        ttlDays = 7,
    )
    private val jwtService = JwtService(props)

    @Test
    fun `createToken returns valid JWT structure`() {
        val token = jwtService.createToken(42L)
        assertTrue(token.isNotBlank())
        assertEquals(3, token.split(".").size, "JWT must have 3 dot-separated segments")
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

    @Test
    fun `extractUserId returns null for expired token`() {
        val expiredProps = JwtProperties(
            secret = "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktMzI=",
            ttlDays = 0,
        )
        val expiredService = JwtService(expiredProps)
        val token = expiredService.createToken(1L)
        assertNull(jwtService.extractUserId(token))
    }
}
