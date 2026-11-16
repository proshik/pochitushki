package ru.proshik.pochitushki.service.miniapp

import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import tools.jackson.databind.json.JsonMapper

/** The Telegram account that opened the Mini App. */
data class MiniAppUser(
    val id: Long,
    val firstName: String?,
    val username: String?,
    val languageCode: String?,
)

/** Verified launch data of one Mini App session. */
data class MiniAppInitData(
    val user: MiniAppUser,
    val authDate: Instant,
    /** Only present when launched by a `t.me/<bot>?startapp=…` link. */
    val startParam: String?,
)

class InitDataException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { MALFORMED, MISSING_HASH, INVALID_HASH, EXPIRED, NO_USER }
}

/**
 * Validates `Telegram.WebApp.initData` against the bot token, as described at
 * https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app:
 *
 * ```
 * secret_key = HMAC_SHA256(key = "WebAppData", message = bot_token)
 * hash       = hex(HMAC_SHA256(key = secret_key, message = data_check_string))
 * ```
 * `data_check_string` is every received field except `hash` — `signature` included, the docs
 * say "all received fields" — as `key=value` with the value decoded once, sorted by key and
 * joined by `\n`. The signature is checked before freshness, and compared in constant time.
 */
object InitData {

    val MAX_AGE: Duration = Duration.ofHours(24)

    private val mapper = JsonMapper.builder().build()

    fun validate(raw: String, botToken: String, now: Instant, maxAge: Duration = MAX_AGE): MiniAppInitData {
        val fields = parse(raw)
        val received = fields["hash"]?.takeIf { it.isNotEmpty() } ?: fail(InitDataException.Reason.MISSING_HASH)
        val authSeconds = fields["auth_date"]?.toLongOrNull() ?: fail(InitDataException.Reason.MALFORMED)

        if (!hashMatches(fields, received, botToken)) fail(InitDataException.Reason.INVALID_HASH)

        val authDate = Instant.ofEpochSecond(authSeconds)
        if (Duration.between(authDate, now) > maxAge) fail(InitDataException.Reason.EXPIRED)

        val user = fields["user"]?.let(::parseUser) ?: fail(InitDataException.Reason.NO_USER)
        return MiniAppInitData(user, authDate, fields["start_param"]?.takeIf { it.isNotEmpty() })
    }

    /** A repeated key is refused: which copy was signed would be a guess. */
    private fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) fail(InitDataException.Reason.MALFORMED)
        val fields = LinkedHashMap<String, String>()
        for (pair in raw.split("&")) {
            val eq = pair.indexOf('=')
            if (eq <= 0) fail(InitDataException.Reason.MALFORMED)
            val key = decode(pair.substring(0, eq))
            val value = decode(pair.substring(eq + 1))
            if (fields.put(key, value) != null) fail(InitDataException.Reason.MALFORMED)
        }
        return fields
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        fail(InitDataException.Reason.MALFORMED)
    }

    private fun hashMatches(fields: Map<String, String>, received: String, botToken: String): Boolean {
        val expected = try {
            HexFormat.of().parseHex(received)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val checkString = fields.filterKeys { it != "hash" }.toSortedMap().entries
            .joinToString("\n") { "${it.key}=${it.value}" }
        val secret = hmac("WebAppData".toByteArray(), botToken.toByteArray())
        return MessageDigest.isEqual(expected, hmac(secret, checkString.toByteArray()))
    }

    private fun parseUser(json: String): MiniAppUser {
        val node = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            fail(InitDataException.Reason.MALFORMED)
        }
        val id = node.get("id")?.takeIf { it.isNumber }?.asLong() ?: fail(InitDataException.Reason.NO_USER)
        return MiniAppUser(
            id = id,
            firstName = node.get("first_name")?.asString(),
            username = node.get("username")?.asString(),
            languageCode = node.get("language_code")?.asString(),
        )
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    private fun fail(reason: InitDataException.Reason): Nothing = throw InitDataException(reason)
}
