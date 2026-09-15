package ru.proshik.pochitushki.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.slf4j.LoggerFactory

class OpenhtmlPdfGenerator(
    private val urlSecurityValidator: UrlSecurityValidator,
) : PdfGenerator {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val cyrillicFont: File? = findCyrillicFont()

    init {
        if (cyrillicFont != null) {
            logger.info("Found Cyrillic-capable font: {}", cyrillicFont.absolutePath)
        } else {
            logger.warn("No Cyrillic-capable font found. PDF files may not render Cyrillic text correctly.")
        }
    }

    override fun generatePdf(url: String): ByteArray {
        logger.debug("generatePdf: url={}", url)

        // SSRF guard: the page and all its sub-resources are fetched server-side.
        urlSecurityValidator.validate(url)

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; Pochitushki/1.0)")
            .followRedirects(false)
            .timeout(15000)
            .get()

        val title = doc.title()

        // Handle lazy-loaded images before cleaning (data-src → src)
        for (img in doc.select("img[data-src]")) {
            if (img.attr("src").isBlank() || img.attr("src").startsWith("data:")) {
                img.attr("src", img.attr("data-src"))
            }
        }

        val cleaner = Cleaner(Safelist.relaxed())
        val cleanDoc = cleaner.clean(doc)

        // Inline every remote image as a data URI so the renderer performs no network I/O of
        // its own. Handing it a remote src would put the fetch outside our SSRF guard: the
        // guard vets the URL we were given, while the renderer would follow redirects from it.
        //
        // Bounded twice over, because the heap is 256 MB and OOM kills the whole process
        // (bot included): at most MAX_IMAGES images, and at most MAX_TOTAL_IMAGE_BYTES across
        // all of them. Without the budget a page of a hundred 10 MB images was a way for any
        // user to take the service down with one link.
        var inlinedImages = 0
        var inlinedBytes = 0L
        for (img in cleanDoc.select("img[src]")) {
            val src = img.attr("src")
            if (src.isBlank() || src.startsWith("data:")) continue

            if (inlinedImages >= MAX_IMAGES || inlinedBytes >= MAX_TOTAL_IMAGE_BYTES) {
                img.remove()
                continue
            }

            val resolved = img.absUrl("src").ifBlank { resolveUrl(url, src) }
            val dataUri = inlineImage(resolved, MAX_TOTAL_IMAGE_BYTES - inlinedBytes)
            if (dataUri != null) {
                img.attr("src", dataUri)
                inlinedImages++
                inlinedBytes += dataUri.length.toLong()
            } else {
                logger.debug("Removing image that could not be inlined: {}", resolved)
                img.remove()
            }
        }

        cleanDoc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8")

        val bodyContent = cleanDoc.body().html()
        val xhtml = buildXhtml(xmlEscape(title), xmlEscape(url), bodyContent)

        val os = ByteArrayOutputStream()
        val builder = PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(xhtml, url)
            .toStream(os)

        if (cyrillicFont != null) {
            builder.useFont(cyrillicFont, FONT_FAMILY)
        }

        builder.run()

        logger.info("generatePdf success: url={}, size={}", url, os.size())

        return os.toByteArray()
    }

    private fun buildXhtml(title: String, url: String, bodyContent: String): String {
        val fontFamily = if (cyrillicFont != null) "'$FONT_FAMILY', serif" else "serif"
        val codeFontFamily = if (cyrillicFont != null) "'$FONT_FAMILY', monospace" else "monospace"

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
  "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <meta charset="UTF-8"/>
    <title>$title</title>
    <style>
        body { font-family: $fontFamily; font-size: 12pt; line-height: 1.6; margin: 40px; }
        h1 { font-size: 18pt; margin-bottom: 10px; }
        h2 { font-size: 16pt; }
        h3 { font-size: 14pt; }
        p { margin-bottom: 8px; }
        a { color: #0066cc; }
        pre { background-color: #f5f5f5; padding: 10px; font-size: 10pt; font-family: $codeFontFamily; }
        code { font-family: $codeFontFamily; font-size: 10pt; }
        img { max-width: 100%; height: auto; }
        blockquote { border-left: 3px solid #ccc; margin-left: 0; padding-left: 15px; color: #666; }
    </style>
</head>
<body>
    <h1>$title</h1>
    <p><a href="$url">$url</a></p>
    <hr/>
    $bodyContent
</body>
</html>"""
    }

    /**
     * Fetches [imageUrl] and returns it as a data URI, or null if it must be dropped.
     *
     * Formats PDFBox embeds directly are passed through untouched; anything else (WebP, TIFF, …)
     * is decoded and re-encoded as PNG.
     */
    private fun inlineImage(imageUrl: String, remainingBudget: Long): String? {
        val limit = minOf(MAX_IMAGE_BYTES.toLong(), remainingBudget).toInt()
        if (limit <= 0) return null

        val (bytes, contentType) = fetchImage(imageUrl, limit) ?: return null

        val mediaType = contentType ?: guessMediaTypeFromPath(imageUrl)
        if (mediaType in DIRECTLY_EMBEDDABLE_MEDIA_TYPES) {
            return "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }

        // A format PDFBox cannot embed has to be decoded first, and decoding is where the
        // bytes stop bounding the memory: a few KB of WebP or TIFF can declare 16000x16000
        // and allocate a gigabyte of raster. Read the header, check the pixel count, and only
        // then decode. ImageIO.read would otherwise throw OutOfMemoryError, which `catch
        // (Exception)` does not catch and which takes the JVM down with ExitOnOutOfMemoryError.
        if (!isDecodableSize(bytes, imageUrl)) return null

        return try {
            val image = bytes.inputStream().use { ImageIO.read(it) } ?: return null
            val pngBytes = ByteArrayOutputStream().use { baos ->
                ImageIO.write(image, "png", baos)
                baos.toByteArray()
            }
            logger.debug("Re-encoded image to PNG: {} ({} bytes)", imageUrl, pngBytes.size)
            "data:image/png;base64,${Base64.getEncoder().encodeToString(pngBytes)}"
        } catch (e: Exception) {
            logger.warn("Failed to convert image: {} => {}", imageUrl, e.message)
            null
        }
    }

    /**
     * Fetches a remote image with the SSRF guard applied to the request that is actually made.
     *
     * Redirects are refused rather than followed. [UrlSecurityValidator] can only vet the URL it
     * is handed, and `HttpURLConnection` follows redirects by default — so a public URL that
     * 3xx-redirects to 169.254.169.254 or an RFC1918 address would otherwise walk straight past
     * the guard. Article images have no legitimate need for a redirect, so refusing is simpler
     * and safer than following-and-revalidating.
     */
    /**
     * True when the image header declares a pixel count we are willing to decode.
     * Unreadable headers are refused rather than decoded blindly.
     */
    private fun isDecodableSize(bytes: ByteArray, imageUrl: String): Boolean = try {
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) {
                logger.debug("No ImageIO reader for image: {}", imageUrl)
                false
            } else {
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val pixels = reader.getWidth(0).toLong() * reader.getHeight(0).toLong()
                    if (pixels > MAX_IMAGE_PIXELS) {
                        logger.debug("Skipping oversized image ({} px): {}", pixels, imageUrl)
                        false
                    } else {
                        true
                    }
                } finally {
                    reader.dispose()
                }
            }
        }
    } catch (e: Exception) {
        logger.debug("Could not read image header: {} => {}", imageUrl, e.message)
        false
    }

    private fun fetchImage(imageUrl: String, maxBytes: Int): Pair<ByteArray, String?>? {
        if (!urlSecurityValidator.isAllowed(imageUrl)) {
            logger.debug("Refusing image with disallowed URL: {}", imageUrl)
            return null
        }

        return try {
            val connection = URI(imageUrl).toURL().openConnection() as? HttpURLConnection
            if (connection == null) {
                logger.debug("Refusing non-HTTP image URL: {}", imageUrl)
                return null
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", USER_AGENT)

            if (connection.responseCode !in 200..299) {
                logger.debug("Skipping image, HTTP {}: {}", connection.responseCode, imageUrl)
                return null
            }

            // Cap the download to avoid decompression-bomb / huge images exhausting memory.
            val bytes = connection.inputStream.use { readUpTo(it, maxBytes) }
                ?: run {
                    logger.debug("Skipping oversized image (> {} bytes): {}", maxBytes, imageUrl)
                    return null
                }

            bytes to connection.contentType?.substringBefore(';')?.trim()?.lowercase()
        } catch (e: Exception) {
            logger.warn("Failed to fetch image: {} => {}", imageUrl, e.message)
            null
        }
    }

    private fun guessMediaTypeFromPath(url: String): String? {
        val path = try {
            URI(url).path?.lowercase() ?: ""
        } catch (e: Exception) {
            url.lowercase()
        }
        return EXTENSION_MEDIA_TYPES.entries.firstOrNull { path.endsWith(it.key) }?.value
    }

    /** Read up to [limit] bytes; return null if the stream has more (oversized). */
    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun resolveUrl(baseUrl: String, relative: String): String {
        return try {
            URI(baseUrl).resolve(relative).toString()
        } catch (e: Exception) {
            relative
        }
    }

    private fun xmlEscape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    companion object {
        private const val FONT_FAMILY = "document-font"
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024 // 10 MB per image
        /** Across the whole document, counted as base64 characters actually embedded. */
        private const val MAX_TOTAL_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_IMAGES = 50
        /** ~25 megapixels: an A4 page at 600 dpi is 35 MP, a photo from a phone is 12. */
        private const val MAX_IMAGE_PIXELS = 25_000_000L
        private const val USER_AGENT = "Mozilla/5.0 (compatible; Pochitushki/1.0)"

        /** Embedded as-is — no decode/re-encode round trip. */
        private val DIRECTLY_EMBEDDABLE_MEDIA_TYPES =
            setOf("image/png", "image/jpeg", "image/gif", "image/bmp")

        private val EXTENSION_MEDIA_TYPES = mapOf(
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".gif" to "image/gif",
            ".bmp" to "image/bmp",
        )

        private val FONT_SEARCH_PATHS = listOf(
            // Linux (Debian/Ubuntu) — DejaVu Sans
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            // Linux — Liberation Sans
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            // Linux — Noto Sans
            "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
            // macOS — Arial Unicode (full Unicode coverage)
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            // macOS — Arial
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            // macOS — Helvetica
            "/System/Library/Fonts/Helvetica.ttc",
        )

        private fun findCyrillicFont(): File? {
            return FONT_SEARCH_PATHS
                .map { File(it) }
                .firstOrNull { it.exists() && it.canRead() }
        }
    }
}
