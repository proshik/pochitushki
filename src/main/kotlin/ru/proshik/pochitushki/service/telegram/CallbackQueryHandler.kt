package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.CallbackQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.TelegramService

/**
 * Handler for Telegram callback queries.
 */
@Component
class CallbackQueryHandler(
    private val telegramService: TelegramService,
) : TelegramUpdateHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun registerHandlers(dispatcher: Dispatcher) {
        dispatcher.callbackQuery { handleCallbacks(callbackQuery) }
    }

    fun parseCallbackData(callbackData: String): Pair<String, String> {
        val (action, data) = callbackData.split("|")

        return Pair(action, data)
    }

    private fun handleCallbacks(callbackQuery: CallbackQuery) {
        val message = callbackQuery.message ?: return

        val chatId = message.chat.id
        val messageId = message.messageId

        val callbackData = callbackQuery.data

        val (action, data) = parseCallbackData(callbackData)
        logger.debug("handleCallbacks: action={}, data={}", action, data)

        when (action) {
            TelegramKeyboard.CALLBACK_ARCHIVE_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.archivePost(chatId, message.messageId, postId)
            }

            TelegramKeyboard.CALLBACK_DELETE_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.deletePost(chatId, message.messageId, postId, PostType.UNREAD)
            }

            TelegramKeyboard.CALLBACK_UNREAD_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.unreadPost(chatId, message.messageId, postId)
            }

            TelegramKeyboard.CALLBACK_DELETE_ARCHIVE_POST -> {
                val postId = data.toLong() // TODO check exception
                telegramService.deletePost(chatId, message.messageId, postId, PostType.ARCHIVE)
            }

            TelegramKeyboard.CALLBACK_NEXT_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.UNREAD)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.UNREAD)
            }

            TelegramKeyboard.CALLBACK_NEXT_ARCHIVE_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.ARCHIVE)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_ARCHIVE_POSTS -> {
                val offset = data.toInt() // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.ARCHIVE)
            }

            else -> throw RuntimeException("Unknown callback action $action")
        }
    }
}
