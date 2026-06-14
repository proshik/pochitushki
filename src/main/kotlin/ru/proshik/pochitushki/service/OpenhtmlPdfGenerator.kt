package ru.proshik.pochitushki.service

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import java.io.ByteArrayOutputStream
import java.io.File
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

        // Resolve relative image URLs to absolute; convert unsupported formats to PNG
        for (img in cleanDoc.select("img[src]")) {
            val src = img.attr("src")
            if (src.isNotBlank() && !src.startsWith("data:")) {
                val resolved = img.absUrl("src").ifBlank { resolveUrl(url, src) }
                // SSRF guard: every remote image is fetched server-side (by the renderer for
                // supported formats, or by convertToDataPng otherwise). Drop non-public targets.
                if (!urlSecurityValidator.isAllowed(resolved)) {
                    logger.debug("Removing image with disallowed URL: {}", resolved)
                    img.remove()
                } else if (isSupportedImageFormat(resolved)) {
                    img.attr("src", resolved)
                } else {
                    val dataUri = convertToDataPng(resolved)
                    if (dataUri != null) {
                        img.attr("src", dataUri)
                    } else {
                        logger.debug("Removing unconvertible image: {}", resolved)
                        img.remove()
                    }
                }
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

    private fun convertToDataPng(imageUrl: String): String? {
        return try {
            val connection = URI(imageUrl).toURL().openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Pochitushki/1.0)")

            // Cap the download to avoid decompression-bomb / huge images exhausting memory.
            val imageBytes = connection.getInputStream().use { readUpTo(it, MAX_IMAGE_BYTES) }
                ?: run {
                    logger.debug("Skipping oversized image (> {} bytes): {}", MAX_IMAGE_BYTES, imageUrl)
                    return null
                }

            val image = imageBytes.inputStream().use { ImageIO.read(it) }
                ?: return null

            val pngBytes = ByteArrayOutputStream().use { baos ->
                ImageIO.write(image, "png", baos)
                baos.toByteArray()
            }

            val base64 = Base64.getEncoder().encodeToString(pngBytes)
            logger.debug("Converted image to PNG data URI: {} ({} bytes)", imageUrl, pngBytes.size)
            "data:image/png;base64,$base64"
        } catch (e: Exception) {
            logger.warn("Failed to convert image: {} => {}", imageUrl, e.message)
            null
        }
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

    private fun isSupportedImageFormat(url: String): Boolean {
        val path = try {
            URI(url).path?.lowercase() ?: ""
        } catch (e: Exception) {
            url.lowercase()
        }
        return SUPPORTED_IMAGE_EXTENSIONS.any { path.endsWith(it) }
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
        private val SUPPORTED_IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp")

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
