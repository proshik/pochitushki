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
                val postId = data.toLong()
                // TODO check exception
                telegramService.toArchivePost(chatId, message.messageId, postId)
            }

            TelegramKeyboard.CALLBACK_DELETE_POST -> {
                val postId = data.toLong()
                // TODO check exception
                telegramService.toDeletePost(chatId, message.messageId, postId, PostType.UNREAD)
            }

            TelegramKeyboard.CALLBACK_UNREAD_POST -> {
                val postId = data.toLong()
                // TODO check exception
                telegramService.toUnreadPost(chatId, message.messageId, postId)
            }

            TelegramKeyboard.CALLBACK_DELETE_ARCHIVE_POST -> {
                val postId = data.toLong()
                // TODO check exception
                telegramService.toDeletePost(chatId, message.messageId, postId, PostType.ARCHIVE)
            }
            /**
             * Random post
             */
            TelegramKeyboard.CALLBACK_RANDOM_POST_ARCHIVE -> {
                val postId = data.toLong()
                telegramService.archivePost(chatId, message.messageId, postId)
                telegramService.getRandomPost(chatId, message.messageId, true)
            }
            TelegramKeyboard.CALLBACK_RANDOM_POST_DELETE -> {
                val postId = data.toLong()
                telegramService.deletePost(chatId, message.messageId, postId, PostType.UNREAD)
                telegramService.getRandomPost(chatId, message.messageId, true)
            }
            TelegramKeyboard.CALLBACK_NEXT_RANDOM_POST -> {
                telegramService.getRandomPost(chatId, message.messageId, true)
            }
            /**
             * Toggle favorite
             */
            TelegramKeyboard.CALLBACK_TOGGLE_UNREAD_FAVORITE -> {
                val postId = data.toLong()
                telegramService.toggleFavorite(chatId, message.messageId, postId, PostType.UNREAD)
            }
            TelegramKeyboard.CALLBACK_TOGGLE_ARCHIVE_FAVORITE -> {
                val postId = data.toLong()
                telegramService.toggleFavorite(chatId, message.messageId, postId, PostType.ARCHIVE)
            }
            TelegramKeyboard.CALLBACK_TOGGLE_RANDOM_FAVORITE -> {
                val postId = data.toLong()
                telegramService.toggleFavoriteForRandomPost(chatId, message.messageId, postId)
            }
            TelegramKeyboard.CALLBACK_FAVORITES_TO_ARCHIVE -> {
                val postId = data.toLong()
                telegramService.favoritesToArchive(chatId, message.messageId, postId)
            }
            TelegramKeyboard.CALLBACK_FAVORITES_TO_UNREAD -> {
                val postId = data.toLong()
                telegramService.favoritesToUnread(chatId, message.messageId, postId)
            }
            /**
             * PDF generation — engine selection
             */
            TelegramKeyboard.CALLBACK_PDF_UNREAD_POST -> {
                val postId = data.toLong()
                telegramService.showPdfEngineSelection(chatId, postId, PostType.UNREAD)
            }
            TelegramKeyboard.CALLBACK_PDF_ARCHIVE_POST -> {
                val postId = data.toLong()
                telegramService.showPdfEngineSelection(chatId, postId, PostType.ARCHIVE)
            }
            /**
             * PDF generation — with selected engine
             */
            TelegramKeyboard.CALLBACK_PDF_ENGINE -> {
                val parts = data.split("_", limit = 3)
                val postId = parts[0].toLong()
                val postType = if (parts[1] == "a") PostType.ARCHIVE else PostType.UNREAD
                val engine = parts[2]
                telegramService.sendPostPdf(chatId, postId, postType, engine)
            }
            /**
             * Unread feed navigation
             */
            TelegramKeyboard.CALLBACK_NEXT_POSTS -> {
                val offset = data.toInt()
                // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.UNREAD)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_POSTS -> {
                val offset = data.toInt()
                // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.UNREAD)
            }
            /**
             * Archive feed navigation
             */
            TelegramKeyboard.CALLBACK_NEXT_ARCHIVE_POSTS -> {
                val offset = data.toInt()
                // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.ARCHIVE)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_ARCHIVE_POSTS -> {
                val offset = data.toInt()
                // TODO check exception
                telegramService.getFeed(chatId, messageId, offset, PostType.ARCHIVE)
            }
            /**
             * Favorites feed navigation
             */
            TelegramKeyboard.CALLBACK_NEXT_FAVORITES -> {
                val offset = data.toInt()
                telegramService.getFeed(chatId, messageId, offset, PostType.FAVORITES)
            }

            TelegramKeyboard.CALLBACK_PREVIOUS_FAVORITES -> {
                val offset = data.toInt()
                telegramService.getFeed(chatId, messageId, offset, PostType.FAVORITES)
            }
            /**
             * Profile settings block
             */
            TelegramKeyboard.CALLBACK_PROFILE_FEED_SETTINGS -> {
                // TODO check exception
                telegramService.getFeedSettings(chatId, messageId)
            }

            TelegramKeyboard.CALLBACK_PROFILE_LANGUAGE_SETTINGS -> {
                // TODO check exception
                telegramService.getLanguageSettings(chatId, messageId)
            }

            TelegramKeyboard.CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE -> {
                val languageCode = data
                // TODO check exception
                telegramService.updateUserSettingsLanguageCode(chatId, messageId, languageCode)
            }

            TelegramKeyboard.CALLBACK_PROFILE_FEED_SETTINGS_CHANGE -> {
                val tgFeedEntriesNumber = data.toInt()
                telegramService.updateUserSettingsFeedCount(chatId, messageId, tgFeedEntriesNumber)
            }

            TelegramKeyboard.CALLBACK_PROFILE_BACK_SETTINGS -> {
                telegramService.showProfile(chatId, messageId)
            }

            else -> throw RuntimeException("Unknown callback action $action")
        }

        logger.info("handleCallbacks success: chatId={}, action={}, data={}", chatId, action, data)
    }
}
