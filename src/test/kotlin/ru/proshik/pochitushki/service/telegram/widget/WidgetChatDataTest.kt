package ru.proshik.pochitushki.service.telegram.widget

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.configuration.MessageConfiguration
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.service.CoverService
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.telegram.card.CardAction
import ru.proshik.pochitushki.service.telegram.card.CardList
import ru.proshik.pochitushki.service.telegram.card.CardRef
import ru.proshik.pochitushki.service.telegram.card.CardView
import ru.proshik.pochitushki.service.telegram.card.PostCardRenderer
import ru.proshik.pochitushki.service.telegram.card.PostLoc
import ru.proshik.pochitushki.service.telegram.card.cardCallback
import tools.jackson.databind.json.JsonMapper

class WidgetChatDataTest {

    private val i18n = I18nService(MessageConfiguration().messageSource())
    private val chatData = WidgetChatData(CoverService(), i18n, PostCardRenderer(CoverService()))
    private val mapper = JsonMapper.builder().build()

    private fun post(title: String, url: String = "https://example.com/a", labels: List<LabelData> = emptyList()) = PostData(
        id = 7, title = title, url = url, userId = 1, tags = null, labels = labels,
        createdDate = LocalDateTime.of(2026, 9, 20, 10, 0), updatedDate = LocalDateTime.now(),
    )

    private fun view(post: PostData, offset: Int = 0, postId: Long = 7) =
        CardView.Post(post, CardRef(CardList.UNREAD, offset, postId, PostLoc.UNREAD, widget = true), 3)

    @Test
    fun `carries the card`() {
        val json = mapper.readTree(chatData.encode(view(post("Hello")), "en"))
        assertEquals("U:0:7:u", json.get("r").asString())
        assertEquals("Hello", json.get("t").asString())
        assertEquals("https://example.com/a", json.get("u").asString())
        assertEquals("example.com", json.get("d").asString())
        assertEquals(3, json.get("n").asInt())
        assertEquals(i18n.getMessage("card.button.archive", "en"), json.get("s").get("a").asString())
    }

    @Test
    fun `worst case fits 1024 bytes`() {
        val labels = (1..20L).map { LabelData(it, "м".repeat(50)) }
        val encoded = chatData.encode(view(post("Я".repeat(2000), url = "https://e.com/" + "x".repeat(242), labels = labels)), "ru")
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 1024, "chat_data is ${encoded.toByteArray().size} bytes")
        assertEquals("U:0:7:u", mapper.readTree(encoded).get("r").asString())
    }

    @Test
    fun `thousands of labels are cheap`() {
        val labels = (1..10_000L).map { LabelData(it, "label-$it-" + "x".repeat(30)) }
        val started = System.nanoTime()
        val encoded = chatData.encode(view(post("T", labels = labels)), "en")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(encoded.toByteArray().size <= 1024)
        assertTrue(millis < 500, "encoding took $millis ms")
    }

    @Test
    fun `links openLink refuses are left out`() {
        for (url in listOf(
            "http://example.com/article", "https://t.me/channel/123", "https://telegram.me/x",
            "https://reader@example.com/article", "https://reader:secret@example.com/article",
        )) {
            assertFalse(mapper.readTree(chatData.encode(view(post("T", url = url)), "en")).has("u"), url)
        }
    }

    @Test
    fun `long url is left out`() {
        val json = mapper.readTree(chatData.encode(view(post("T", url = "https://e.com/" + "x".repeat(300))), "en"))
        assertFalse(json.has("u"))
    }

    @Test
    fun `request data fits 64 bytes`() {
        val ref = CardRef(CardList.FAVORITES, 999_999, Long.MAX_VALUE, PostLoc.ARCHIVE, widget = true)
        for (action in listOf(CardAction.NAV, CardAction.ARCHIVE, CardAction.UNREAD, CardAction.DELETE_ASK,
            CardAction.DELETE_YES, CardAction.DELETE_NO, CardAction.FAVORITE, CardAction.RANDOM_NEXT)) {
            val data = cardCallback(action, ref)
            assertTrue(data.toByteArray().size <= 64, "$data is ${data.toByteArray().size} bytes")
        }
    }

    @Test
    fun `empty view`() {
        val json = mapper.readTree(chatData.encode(CardView.Notice("All read"), "en"))
        assertEquals(1, json.get("e").asInt())
        assertEquals("All read", json.get("m").asString())
    }
}
