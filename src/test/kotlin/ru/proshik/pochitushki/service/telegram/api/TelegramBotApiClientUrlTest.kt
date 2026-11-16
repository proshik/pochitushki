package ru.proshik.pochitushki.service.telegram.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.core.env.StandardEnvironment

class TelegramBotApiClientUrlTest {

    private val url = TelegramBotApiClient::class.java.getAnnotation(FeignClient::class.java).url

    /**
     * application.yml has neither telegram.api-url nor telegram.token: their defaults live in
     * TelegramProperties, which Feign's URL placeholder never sees. Tests always set api-url,
     * so only a real start (TELEGRAM_ENABLED=false, no TELEGRAM_TOKEN) showed the failure.
     */
    @Test
    fun `url resolves without any telegram properties`() {
        val expected = ru.proshik.pochitushki.configuration.properties.TelegramProperties(enabled = false).apiUrl + "bot"
        assertEquals(expected, StandardEnvironment().resolveRequiredPlaceholders(url))
    }
}
