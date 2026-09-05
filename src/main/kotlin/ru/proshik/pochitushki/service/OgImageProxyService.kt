package ru.proshik.pochitushki.service

import com.github.benmanes.caffeine.cache.Caffeine
import java.io.IOException
import java.time.Duration
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Serves cover photos through the app instead of letting the browser fetch them
 * from the linked site.
 *
 * Three reasons this proxy exists rather than an `<img src="https://othersite/…">`:
 * the reader's IP and their reading habits stop leaking to every site they saved;
 * the CSP can keep `img-src 'self'` instead of opening up to all of https:; and a
 * shelf that shows the same cover repeatedly hits the origin once, not once per card.
 *
 * The cache is bounded by total bytes, not entry count — entries are images, and
 * a thousand of them is a very different amount of memory than a thousand strings.
 * It lives in memory only: losing it on restart costs a refetch, nothing more.
 */
@Service
class OgImageProxyService(private val urlSecurityValidator: UrlSecurityValidator) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class CachedImage(val contentType: String, val bytes: ByteArray) {
        // Data class equality on a ByteArray compares references; images are only
        // ever cache values, never keys, so identity is the honest contract here.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val cache = Caffeine.newBuilder()
        .maximumWeight(MAX_CACHE_BYTES)
        .weigher<String, CachedImage> { _, image -> image.bytes.size }
        .expireAfterWrite(Duration.ofDays(CACHE_TTL_DAYS))
        .build<String, CachedImage>()

    /** Returns the image bytes, or null if the URL is unsafe, unreachable or not a usable image. */
    fun fetch(imageUrl: String): CachedImage? {
        cache.getIfPresent(imageUrl)?.let { return it }

        // Same rule as every other outbound fetch: never let a saved URL point us
        // at something internal (see UrlSecurityValidator).
        if (!urlSecurityValidator.isAllowed(imageUrl)) {
            logger.debug("Refusing to proxy a disallowed image url: {}", imageUrl)
            return null
        }

        val image = load(imageUrl) ?: return null
        cache.put(imageUrl, image)
        return image
    }

    private fun load(imageUrl: String): CachedImage? = try {
        val response = Jsoup.connect(imageUrl)
            // A redirect could land on an internal address after the check above.
            .followRedirects(false)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .timeout(FETCH_TIMEOUT_MS)
            // Jsoup truncates at the cap rather than failing, so a body that lands
            // exactly on it is treated as "too big" instead of served half-decoded.
            .maxBodySize(MAX_IMAGE_BYTES)
            .execute()

        val contentType = response.contentType()?.substringBefore(';')?.trim()?.lowercase()
        val bytes = response.bodyAsBytes()

        when {
            response.statusCode() != 200 -> null
            contentType !in ALLOWED_CONTENT_TYPES -> {
                // SVG is the reason this is an allowlist: served from our own origin
                // it is a script-bearing document, not a picture.
                logger.debug("Refusing to proxy content-type {} from {}", contentType, imageUrl)
                null
            }
            bytes.isEmpty() || bytes.size >= MAX_IMAGE_BYTES -> null
            else -> CachedImage(contentType!!, bytes)
        }
    } catch (e: IOException) {
        logger.debug("Failed to fetch cover image {}: {}", imageUrl, e.message)
        null
    }

    companion object {
        const val CACHE_CONTROL = "private, max-age=604800"

        private const val MAX_CACHE_BYTES = 48L * 1024 * 1024
        private const val CACHE_TTL_DAYS = 7L
        private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        private const val FETCH_TIMEOUT_MS = 5000

        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/avif",
        )
    }
}
