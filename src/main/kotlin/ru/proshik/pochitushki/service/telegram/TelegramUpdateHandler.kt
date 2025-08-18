package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import org.springframework.stereotype.Component

/**
 * Interface for handling Telegram updates.
 * Implementations will register their handlers with the Telegram bot dispatcher.
 */
@Component
interface TelegramUpdateHandler {
    /**
     * Register handlers with the Telegram bot dispatcher.
     *
     * @param dispatcher The Telegram bot dispatcher
     */
    fun registerHandlers(dispatcher: Dispatcher)
}