package ru.proshik.pochitushki.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.configuration.BotProvider

class TelegramControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var botProvider: BotProvider


    @Test
    fun `should return welcome message`() {
        // given

        // when
        println("test")

        // then
    }
}