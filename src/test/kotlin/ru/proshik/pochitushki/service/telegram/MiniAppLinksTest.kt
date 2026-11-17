package ru.proshik.pochitushki.service.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.configuration.MessageConfiguration
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.telegram.card.CardList

class MiniAppLinksTest {

    private val i18n = I18nService(MessageConfiguration().messageSource())

    private fun links(url: String?) = MiniAppLinks(TelegramProperties(enabled = true, miniAppUrl = url), i18n)

    @Test
    fun `https url gives links per list`() {
        val links = links("https://app.test/tg")
        assertEquals("https://app.test/tg", links.url())
        assertEquals("https://app.test/tg", links.url(CardList.UNREAD))
        assertEquals("https://app.test/tg?p=archive", links.url(CardList.ARCHIVE))
        assertEquals("https://app.test/tg?p=favorites", links.url(CardList.FAVORITES))
        assertEquals("https://app.test/tg?p=random", links.url(CardList.RANDOM))
    }

    @Test
    fun `http url gives no link`() {
        // Telegram refuses a whole sendMessage whose keyboard has a non-https web_app button.
        assertNull(links("http://localhost:8080/tg").url())
    }

    @Test
    fun `blank url gives no link`() {
        assertNull(links(null).url())
        assertNull(links("").url())
        assertNull(links("/tg").url())
    }
}
