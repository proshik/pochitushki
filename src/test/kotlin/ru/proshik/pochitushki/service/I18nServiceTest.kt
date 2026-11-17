package ru.proshik.pochitushki.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.proshik.pochitushki.BaseIntegrationTest

class I18nServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var i18nService: I18nService

    @Test
    fun `getMessage returns English message`() {
        val message = i18nService.getMessage("card.button.archive", "en")
        assertEquals("✓ Archive", message)
    }

    @Test
    fun `getMessage returns Russian message`() {
        val message = i18nService.getMessage("card.button.archive", "ru")
        assertEquals("✓ В архив", message)
    }

    @Test
    fun `getMessage falls back to English for unknown locale`() {
        val message = i18nService.getMessage("card.button.archive", "fr")
        assertEquals("✓ Archive", message)
    }

    @Test
    fun `getMessage with args interpolates correctly`() {
        val message = i18nService.getMessage("command.labels.attached", "en", arrayOf(42))
        assertTrue(message.contains("42"))
    }
}
