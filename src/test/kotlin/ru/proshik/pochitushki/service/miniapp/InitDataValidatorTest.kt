package ru.proshik.pochitushki.service.miniapp

import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InitDataValidatorTest {

    // A real init data string and the bot token that signed it, published at
    // https://docs.telegram-mini-apps.com/platform/init-data (the same vector Pacer pins).
    private val exampleToken = "5768337691:AAH5YkoiEuPk8-FZa32hStHTqXiLPtAEhx8"
    private val exampleInitData = "query_id=AAHdF6IQAAAAAN0XohDhrOrc" +
        "&user=%7B%22id%22%3A279058397%2C%22first_name%22%3A%22Vladislav%22%2C%22last_name%22%3A%22Kibenko%22" +
        "%2C%22username%22%3A%22vdkfrost%22%2C%22language_code%22%3A%22ru%22%2C%22is_premium%22%3Atrue%7D" +
        "&auth_date=1662771648" +
        "&hash=c501b71e775f74ce10e377dea85a7ea24ecd640b223ea86dfe453e0eaed2e2b2"
    private val signedAt = Instant.ofEpochSecond(1662771648)
    private val now = signedAt.plusSeconds(60)

    private fun reason(raw: String, token: String = exampleToken, at: Instant = now) =
        assertThrows<InitDataException> { InitData.validate(raw, token, at) }.reason

    @Test
    fun `accepts the published example`() {
        val data = InitData.validate(exampleInitData, exampleToken, now)
        assertEquals(279058397L, data.user.id)
        assertEquals("Vladislav", data.user.firstName)
        assertEquals("vdkfrost", data.user.username)
        assertEquals("ru", data.user.languageCode)
        assertEquals(signedAt, data.authDate)
        assertNull(data.startParam)
    }

    @Test
    fun `rejects another bot's token`() {
        assertEquals(InitDataException.Reason.INVALID_HASH, reason(exampleInitData, token = "123456:another-bot"))
    }

    @Test
    fun `rejects a tampered field`() {
        assertEquals(InitDataException.Reason.INVALID_HASH, reason(exampleInitData.replace("279058397", "279058398")))
    }

    @Test
    fun `rejects data older than 24h`() {
        assertEquals(InitDataException.Reason.EXPIRED, reason(exampleInitData, at = signedAt.plus(Duration.ofHours(25))))
    }

    @Test
    fun `rejects missing hash`() {
        assertEquals(InitDataException.Reason.MISSING_HASH, reason(exampleInitData.substringBefore("&hash=")))
    }

    @Test
    fun `rejects a duplicated key`() {
        assertEquals(InitDataException.Reason.MALFORMED, reason("auth_date=1&$exampleInitData"))
    }

    @Test
    fun `rejects a non-hex hash`() {
        assertEquals(InitDataException.Reason.INVALID_HASH, reason(exampleInitData.substringBefore("&hash=") + "&hash=zz"))
    }

    @Test
    fun `rejects data without user`() {
        val raw = sign(mapOf("auth_date" to "${now.epochSecond}", "query_id" to "q"), exampleToken)
        assertEquals(InitDataException.Reason.NO_USER, reason(raw))
    }

    @Test
    fun `signature takes part in the check`() {
        val fields = mapOf(
            "auth_date" to "${now.epochSecond}",
            "user" to """{"id":1,"first_name":"A"}""",
            "signature" to "c2lnbmF0dXJl",
        )
        val raw = sign(fields, exampleToken)
        assertEquals(1L, InitData.validate(raw, exampleToken, now).user.id)

        val withoutSignature = raw.split("&").filterNot { it.startsWith("signature=") }.joinToString("&")
        assertEquals(InitDataException.Reason.INVALID_HASH, reason(withoutSignature))
    }

    @Test
    fun `start_param is returned`() {
        val raw = sign(
            mapOf("auth_date" to "${now.epochSecond}", "user" to """{"id":1}""", "start_param" to "archive"),
            exampleToken,
        )
        assertEquals("archive", InitData.validate(raw, exampleToken, now).startParam)
    }

    /** Signs [fields] the way Telegram does, independently of the code under test. */
    private fun sign(fields: Map<String, String>, token: String): String {
        val checkString = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        val secret = hmac("WebAppData".toByteArray(), token.toByteArray())
        val hash = hmac(secret, checkString.toByteArray()).joinToString("") { "%02x".format(it) }
        return (fields + ("hash" to hash)).entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
        }
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
}
