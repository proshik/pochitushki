package ru.proshik.pochitushki.service.telegram.card

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.service.CoverService

class PostCardRendererTest {

    private val renderer = PostCardRenderer(CoverService())
    private val today = LocalDate.of(2026, 11, 16)

    private fun post(
        title: String?,
        url: String = "https://www.thedecisionlab.com/reference-guide/philosophy/system-1-and-system-2-thinking",
        created: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0),
        labels: List<LabelData> = emptyList(),
    ) = PostData(
        id = 1, title = title, url = url, userId = 1, tags = null,
        labels = labels, createdDate = created, updatedDate = created,
    )

    @Test
    fun `title is a link and fields are escaped`() {
        val text = renderer.render(post("A <b> & \"q\"", url = "https://x.com/?a=1&b=\"2\""), "en", today)
        assertTrue(
            text.html.startsWith("<a href=\"https://x.com/?a=1&amp;b=&quot;2&quot;\">A &lt;b&gt; &amp; &quot;q&quot;</a>"),
            text.html,
        )
    }

    @Test
    fun `second line is domain, date and labels`() {
        val text = renderer.render(
            post("System 1 and System 2 Thinking", labels = listOf(LabelData(1, "психология"), LabelData(2, "kotlin"))),
            "ru", today,
        )
        val second = text.html.lines()[1]
        assertTrue(second.startsWith("thedecisionlab.com · 20 "), second)
        assertTrue(second.endsWith(" · #психология #kotlin"), second)
        assertTrue(!second.contains("2026"), "same year is not repeated: $second")
    }

    @Test
    fun `another year is shown`() {
        val text = renderer.render(post("Old", created = LocalDateTime.of(2024, 3, 1, 0, 0)), "en", today)
        assertTrue(text.html.lines()[1].contains("2024"), text.html)
    }

    @Test
    fun `no title falls back to url`() {
        val text = renderer.render(post(null, url = "https://a.com/x"), "en", today)
        assertTrue(text.html.startsWith("<a href=\"https://a.com/x\">https://a.com/x</a>"), text.html)
    }

    @Test
    fun `preview url is the post url`() {
        assertEquals("https://a.com/x", renderer.render(post("T", url = "https://a.com/x"), "en", today).previewUrl)
    }

    @Test
    fun `huge title is cut and the text stays under 4096 chars`() {
        val labels = (1..50L).map { LabelData(it, "метка-номер-${"x".repeat(20)}-$it") }
        val text = renderer.render(post("Я".repeat(10_000), labels = labels), "ru", today)
        assertTrue(text.html.length <= 4096, "length ${text.html.length}")
        assertTrue(text.html.contains("…</a>"), "the title is visibly cut")
    }
}
