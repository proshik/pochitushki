package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.Update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.UserSettingsLimits
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.TelegramService
import ru.proshik.pochitushki.service.UserService
import ru.proshik.pochitushki.service.telegram.card.CallbackAnswer
import ru.proshik.pochitushki.service.telegram.card.CardAction
import ru.proshik.pochitushki.service.telegram.card.PostCardService
import ru.proshik.pochitushki.service.telegram.card.parseCardCallback

/**
 * Handler for Telegram callback queries.
 */
@Component
class CallbackQueryHandler(
    private val telegramService: TelegramService,
    private val postCardService: PostCardService,
    private val userService: UserService,
    private val i18nService: I18nService,
) : TelegramUpdateHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Not the library's `callbackQuery { }` DSL: that one answers the query itself after the
     * handler, with no text, so a toast would be a second answer Telegram refuses. Here every
     * update gets exactly one answerCallbackQuery — with the toast when there is one, and also
     * when handling failed, or the button keeps spinning until Telegram gives up on it.
     */
    override fun registerHandlers(dispatcher: Dispatcher) {
        dispatcher.addHandler(object : Handler {
            override fun checkUpdate(update: Update): Boolean = update.callbackQuery != null

            override suspend fun handleUpdate(bot: Bot, update: Update) {
                val callbackQuery = update.callbackQuery ?: return
                var answer = CallbackAnswer()
                try {
                    answer = handleCallbacks(callbackQuery)
                } catch (e: Exception) {
                    // A malformed/forged callback must not crash the dispatcher coroutine.
                    logger.warn("callback handling failed: data={}", callbackQuery.data, e)
                } finally {
                    try {
                        bot.answerCallbackQuery(callbackQueryId = callbackQuery.id, text = answer.text)
                    } catch (e: Exception) {
                        logger.warn("answerCallbackQuery failed: {}", e.javaClass.simpleName)
                    }
                }
            }
        })
    }

    fun parseCallbackData(callbackData: String): Pair<String, String> {
        val parts = callbackData.split("|", limit = 2)
        val action = parts[0]
        val data = parts.getOrElse(1) { "" }

        return Pair(action, data)
    }

    private fun handleCallbacks(callbackQuery: CallbackQuery): CallbackAnswer {
        val message = callbackQuery.message ?: return CallbackAnswer()

        val chatId = message.chat.id
        val messageId = message.messageId

        val callbackData = callbackQuery.data

        val (action, data) = parseCallbackData(callbackData)
        logger.debug("handleCallbacks: action={}, data={}", action, data)

        if (action in CardAction.ALL) {
            val (ref, extra) = parseCardCallback(data) ?: run {
                logger.warn("ignoring malformed card callback: {}", callbackData)
                return CallbackAnswer()
            }
            return postCardService.onCallback(chatId, messageId, action, ref, extra)
        }

        when (action) {
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
            TelegramKeyboard.CALLBACK_TOGGLE_RANDOM_FAVORITE -> {
                val postId = data.toLong()
                telegramService.toggleFavoriteForRandomPost(chatId, message.messageId, postId)
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

            // callback_data is attacker-controllable (anyone can post an update with any data to
            // a webhook whose path leaked), so both settings are checked against what the
            // keyboards actually offer instead of being written through.
            TelegramKeyboard.CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE -> {
                if (data !in UserSettingsLimits.SUPPORTED_LANGUAGES) {
                    logger.warn("ignoring unsupported language in callback: {}", data)
                    return CallbackAnswer()
                }
                telegramService.updateUserSettingsLanguageCode(chatId, messageId, data)
            }

            TelegramKeyboard.CALLBACK_PROFILE_FEED_SETTINGS_CHANGE -> {
                val tgFeedEntriesNumber = data.toIntOrNull()
                if (tgFeedEntriesNumber == null || tgFeedEntriesNumber !in UserSettingsLimits.FEED_ENTRIES_RANGE) {
                    logger.warn("ignoring out-of-range feed size in callback: {}", data)
                    return CallbackAnswer()
                }
                telegramService.updateUserSettingsFeedCount(chatId, messageId, tgFeedEntriesNumber)
            }

            TelegramKeyboard.CALLBACK_PROFILE_BACK_SETTINGS -> {
                telegramService.showProfile(chatId, messageId)
            }

            TelegramKeyboard.CALLBACK_PROFILE_IMPORT -> {
                telegramService.showImportInstruction(chatId)
            }

            TelegramKeyboard.CALLBACK_PROFILE_EXPORT -> {
                telegramService.export(chatId, messageId)
            }

            /**
             * Labels
             */
            TelegramKeyboard.CALLBACK_POST_LABELS -> {
                telegramService.showPostLabels(chatId, messageId, TelegramKeyboard.LabelCallbackContext.decode(data))
            }

            TelegramKeyboard.CALLBACK_POST_LABEL_TOGGLE -> {
                // data is "<postId>:<table>:<context>:<labelId>" — the label id is the tail.
                val labelId = data.substringAfterLast(':').toLong()
                val ctx = TelegramKeyboard.LabelCallbackContext.decode(data.substringBeforeLast(':'))
                telegramService.togglePostLabel(chatId, messageId, ctx, labelId)
            }

            TelegramKeyboard.CALLBACK_POST_LABEL_NEW -> {
                telegramService.promptNewLabel(chatId, TelegramKeyboard.LabelCallbackContext.decode(data))
            }

            TelegramKeyboard.CALLBACK_POST_LABELS_BACK -> {
                telegramService.hidePostLabels(chatId, messageId, TelegramKeyboard.LabelCallbackContext.decode(data))
            }

            // Buttons of cards sent by an earlier version stay in chats after a deploy.
            else -> {
                logger.info("outdated callback action: {}", action)
                return CallbackAnswer(outdatedText(chatId))
            }
        }

        logger.info("handleCallbacks success: chatId={}, action={}, data={}", chatId, action, data)
        return CallbackAnswer()
    }

    private fun outdatedText(chatId: Long): String? =
        userService.findUserByChatId(chatId)?.let { i18nService.getMessage("card.toast.outdated", it.settings.languageCode) }
}
