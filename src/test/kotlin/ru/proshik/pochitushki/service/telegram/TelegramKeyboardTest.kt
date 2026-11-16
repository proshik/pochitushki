package ru.proshik.pochitushki.service.telegram

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.configuration.MessageConfiguration
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.telegram.api.InlineKeyboard
import ru.proshik.pochitushki.service.telegram.card.CardList
import ru.proshik.pochitushki.service.telegram.card.CardRef
import ru.proshik.pochitushki.service.telegram.card.CardView
import ru.proshik.pochitushki.service.telegram.card.PostLoc

class TelegramKeyboardTest {

    private val i18n = I18nService(MessageConfiguration().messageSource())
    private fun keyboardWith(miniAppUrl: String?) =
        TelegramKeyboard(i18n, MiniAppLinks(TelegramProperties(enabled = true, miniAppUrl = miniAppUrl), i18n))
    private val keyboard = keyboardWith(null)

    private fun post(id: Long, favorite: Boolean = false, archived: Boolean = false) = PostData(
        id = id, title = "T", url = "https://t.com", userId = 1, tags = null,
        isFavorite = favorite, isArchived = archived,
        createdDate = LocalDateTime.now(), updatedDate = LocalDateTime.now(),
    )

    private fun card(list: CardList, offset: Int, total: Int, postId: Long = 7, loc: PostLoc = PostLoc.UNREAD, favorite: Boolean = false) =
        keyboard.buildCardKeyboard(
            CardView.Post(post(postId, favorite, loc == PostLoc.ARCHIVE), CardRef(list, offset, postId, loc), total),
            "en",
        )

    private fun InlineKeyboard.data(row: Int): List<String?> = rows[row].map { it.callbackData }
    private fun InlineKeyboard.texts(row: Int): List<String> = rows[row].map { it.text }

    @Test
    fun `unread card first row`() {
        val kb = card(CardList.UNREAD, 2, 5)
        assertEquals(listOf("ca|u:2:7:u", "cf|u:2:7:u", "cd|u:2:7:u", "cm|u:2:7:u:1"), kb.data(0))
        assertEquals(i18n.getMessage("card.button.archive", "en"), kb.texts(0)[0])
        assertEquals("☆", kb.texts(0)[1])
    }

    @Test
    fun `favorite shows a full star`() {
        assertEquals("★", card(CardList.UNREAD, 0, 1, favorite = true).texts(0)[1])
    }

    @Test
    fun `archive card offers back to unread`() {
        val kb = card(CardList.ARCHIVE, 0, 3, loc = PostLoc.ARCHIVE)
        assertEquals("cu|a:0:7:a", kb.data(0)[0])
        assertEquals(i18n.getMessage("card.button.unread", "en"), kb.texts(0)[0])
    }

    @Test
    fun `favorites card follows where the post lives`() {
        assertEquals("cu|f:1:7:a", card(CardList.FAVORITES, 1, 3, loc = PostLoc.ARCHIVE).data(0)[0])
        assertEquals("ca|f:1:7:u", card(CardList.FAVORITES, 1, 3, loc = PostLoc.UNREAD).data(0)[0])
    }

    @Test
    fun `edges show inert dots`() {
        val first = card(CardList.UNREAD, 0, 3)
        assertEquals(listOf("·", "1 / 3", "›"), first.texts(1))
        assertEquals(listOf("c0|u:0:7:u", "c0|u:0:7:u", "cn|u:1:7:u"), first.data(1))

        val last = card(CardList.UNREAD, 2, 3)
        assertEquals(listOf("‹", "3 / 3", "·"), last.texts(1))
        assertEquals("cn|u:1:7:u", last.data(1)[0])
    }

    @Test
    fun `expanded card has pdf and labels`() {
        val view = CardView.Post(post(7), CardRef(CardList.UNREAD, 0, 7, PostLoc.UNREAD), 1, expanded = true)
        val kb = keyboard.buildCardKeyboard(view, "en")
        assertEquals(listOf("cp|u:0:7:u", "lp|u:0:7:u"), kb.data(1))
        assertEquals("cm|u:0:7:u:0", kb.data(0)[3])
    }

    @Test
    fun `expanded card opens the app on the same shelf`() {
        val view = CardView.Post(post(7), CardRef(CardList.ARCHIVE, 0, 7, PostLoc.ARCHIVE), 1, expanded = true)
        val app = keyboardWith("https://app.test/tg").buildCardKeyboard(view, "en").rows[1].last()
        assertEquals("https://app.test/tg?p=archive", app.webApp?.url)
        assertEquals(i18n.getMessage("card.button.app", "en"), app.text)
    }

    @Test
    fun `no https url, no app button`() {
        val view = CardView.Post(post(7), CardRef(CardList.UNREAD, 0, 7, PostLoc.UNREAD), 1, expanded = true)
        val kb = keyboardWith("http://localhost:8080/tg").buildCardKeyboard(view, "en")
        assertTrue(kb.buttons().none { it.webApp != null })
        assertEquals(null, keyboardWith("http://localhost:8080/tg").buildStartKeyboard("en"))
    }

    @Test
    fun `engine choice keyboard carries ce and back`() {
        val ref = CardRef(CardList.FAVORITES, 999_999, Long.MAX_VALUE, PostLoc.ARCHIVE)
        val kb = keyboard.buildPdfEngineKeyboard(ref, listOf("openhtml", "playwright"), "en")
        val data = kb.buttons().map { it.callbackData!! }
        assertEquals(
            listOf("ce|${ref.encode()}:openhtml", "ce|${ref.encode()}:playwright", "cm|${ref.encode()}:0"),
            data,
        )
        data.forEach { assertTrue(it.toByteArray().size <= 64, "$it is ${it.toByteArray().size} bytes") }
    }

    @Test
    fun `every button fits 64 bytes`() {
        for (list in listOf(CardList.UNREAD, CardList.ARCHIVE, CardList.FAVORITES)) {
            val kb = card(list, 999_999, 1_000_000, postId = Long.MAX_VALUE, loc = PostLoc.ARCHIVE)
            kb.buttons().mapNotNull { it.callbackData }.forEach {
                assertTrue(it.toByteArray().size <= 64, "$it is ${it.toByteArray().size} bytes")
            }
        }
    }
}
