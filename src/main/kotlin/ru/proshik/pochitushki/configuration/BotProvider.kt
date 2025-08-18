package ru.proshik.pochitushki.configuration

import com.github.kotlintelegrambot.Bot
import org.springframework.stereotype.Component

/**
 * Provider for the Bot instance to avoid direct injection of the final Bot class.
 * This helps prevent Spring from trying to create a CGLIB proxy for the final Bot class.
 */
@Component
class BotProvider  {

    private lateinit var bot: Bot

    fun setBot(bot: Bot) {
        this.bot = bot
    }

    fun getBot(): Bot {
        return bot
    }
}