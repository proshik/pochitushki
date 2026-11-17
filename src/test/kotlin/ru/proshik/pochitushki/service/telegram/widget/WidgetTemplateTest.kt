package ru.proshik.pochitushki.service.telegram.widget

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetTemplateTest {

    private val html = WidgetTemplate().html

    @Test
    fun `fits raw size`() {
        val size = html.toByteArray(Charsets.UTF_8).size
        assertTrue(size <= 32_768, "widget html is $size bytes")
    }

    @Test
    fun `fits compressed size`() {
        // Telegram compresses with zlib at the maximum level and refuses more than 4096 bytes.
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(9)).use { it.write(html.toByteArray(Charsets.UTF_8)) }
        assertTrue(out.size() <= 4096, "widget html is ${out.size()} bytes compressed")
    }

    @Test
    fun `loads nothing external`() {
        for (banned in listOf("src=", "href=", "url(", "@import", "@font-face", "fetch(", "XMLHttpRequest", "\u0000")) {
            assertFalse(html.contains(banned), "widget html contains '$banned'")
        }
    }

    @Test
    fun `palette matches the site`() {
        val css = File("src/main/resources/static/css/app.css").readText()
        val site = Regex("""\.cv-(\d+)\s*\{ --cv-bg: (#\w+); --cv-fg: (#\w+); }""").findAll(css)
            .associate { it.groupValues[1].toInt() to (it.groupValues[2] to it.groupValues[3]) }
        val widget = Regex("""\.cv-(\d+)\{--b:(#\w+);--f:(#\w+)}""").findAll(html)
            .associate { it.groupValues[1].toInt() to (it.groupValues[2] to it.groupValues[3]) }
        assertEquals(12, site.size)
        assertEquals(site, widget)
    }
}
