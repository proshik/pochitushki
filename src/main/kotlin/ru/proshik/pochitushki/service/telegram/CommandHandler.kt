package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.errors.TelegramError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.TelegramService
import ru.proshik.pochitushki.service.TelegramService.Companion.LANGUAGE_EN_CODE
import ru.proshik.pochitushki.service.TelegramService.Companion.supportedLanguages

/**
 * Handler for Telegram commands and text messages.
 * This class handles both command-based interactions (like "/start" and "/logout")
 * and text-based interactions (like button presses).
 */
@Component
class CommandHandler(
    private val i18nService: I18nService,
    private val telegramService: TelegramService,
) : TelegramUpdateHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val COMMAND_START = "start"
        const val COMMAND_HELP = "help"
        const val COMMAND_FEED = "feed"
        const val COMMAND_ARCHIVE = "archive"
        const val COMMAND_RANDOM_POST = "random_post"
        const val COMMAND_PROFILE = "profile"
        const val COMMAND_FAVORITES = "favorites"

        val COMMANDS = listOf(
            "/$COMMAND_START",
            "/$COMMAND_HELP",
            "/$COMMAND_FEED",
            "/$COMMAND_ARCHIVE",
            "/$COMMAND_RANDOM_POST",
            "/$COMMAND_PROFILE",
            "/$COMMAND_FAVORITES",
        )
    }

    override fun registerHandlers(dispatcher: Dispatcher) {
        dispatcher.text { handleCommonText(message) }

        // Register command handlers
        dispatcher.command(COMMAND_START) { handleStartCommand(update) }
        dispatcher.command(COMMAND_HELP) { handleHelpCommand(update) }

        dispatcher.command(COMMAND_FEED) { handleFeedOperation(message) }
        dispatcher.command(COMMAND_RANDOM_POST) { handleRandomPostOperation(message) }
        dispatcher.command(COMMAND_ARCHIVE) { handleArchivePostOperation(message) }
        dispatcher.command(COMMAND_PROFILE) { handleProfileOperations(message) }
        dispatcher.command(COMMAND_FAVORITES) { handleFavoritesOperation(message) }

        dispatcher.message { handleFileUpload(message) }

        dispatcher.telegramError { handleError(error) }
    }

    private fun handleError(error: TelegramError) {
        logger.warn("telegram error: {}", error)
    }

    private fun handleStartCommand(update: Update) {
        if (update.message == null) {
            throw RuntimeException("start command message shouldn't be null: updateId=${update.updateId}")
        }

        val tgLanguageCode = when (update.message?.from?.languageCode) {
            null -> LANGUAGE_EN_CODE
            !in supportedLanguages -> LANGUAGE_EN_CODE
            else -> update.message!!.from!!.languageCode!!
        }

        val userSettings = telegramService.addUser(
            chatId = update.message!!.chat.id,
            username = update.message?.from?.username,
            firstName = update.message?.from?.firstName,
            lastName = update.message?.from?.lastName,
            languageCode = tgLanguageCode
        )

        val chatId = update.message!!.chat.id

        startWelcomeMessage(chatId, userSettings.languageCode)
    }

    private fun handleHelpCommand(update: Update) {
        if (update.message == null) {
            throw RuntimeException("start command message shouldn't be null: updateId=${update.updateId}")
        }

        val chatId = update.message!!.chat.id

        val userSettings = telegramService.getUserSettings(chatId)

        helpMessage(chatId, userSettings.languageCode)
    }

    private fun handleFeedOperation(message: Message) {
        telegramService.getFeed(message.chat.id, message.messageId, postType = PostType.UNREAD)
    }

    private fun handleArchivePostOperation(message: Message) {
        telegramService.getFeed(message.chat.id, message.messageId, postType = PostType.ARCHIVE)
    }

    private fun handleFavoritesOperation(message: Message) {
        telegramService.getFeed(message.chat.id, message.messageId, postType = PostType.FAVORITES)
    }

    private fun handleRandomPostOperation(message: Message) {
        telegramService.getRandomPost(message.chat.id, message.messageId)
    }

    /**
     * Profile operation
     */
    private fun handleProfileOperations(message: Message) {
        val chatId = message.chat.id

        telegramService.showProfile(chatId, message.messageId)
    }

//    private fun handleImportButton(message: Message) {
//        val chatId = message.chat.id
//
//        telegramService.sendMessage(
//            chatId = chatId,
//            text = "Waiting an archive from https://getpocket.com/export in *.zip archive with *.csv files inside",
//        )
//    }

    private fun handleExportButton(message: Message) {
        val chatId = message.chat.id

        telegramService.export(chatId, message.messageId)
    }

    private fun handleFileUpload(message: Message) {
        val chatId = message.chat.id

        if (message.document != null && message.document!!.mimeType == "application/zip") {
            telegramService.import(chatId, message.document!!.fileId)

            return
        }
    }

    private fun handleCommonText(message: Message) {
        if (message.text != null && (message.text in COMMANDS)) {
            return
        }

        if (message.text != null) {
            try {
                telegramService.addPost(message.chat.id, message.messageId, message.text!!)
            } catch (ex: Exception) {
                logger.warn("error adding post message=${message.chat.id}", ex)
                telegramService.showErrorMessage(message.chat.id)
            }
            return
        }

        logger.debug("unknown message type: chatId={}", message.chat.id)
    }

    private fun startWelcomeMessage(chatId: Long, languageCode: String) {
        telegramService.sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = i18nService.getMessage("command.start.message", languageCode)
        )
    }

    private fun helpMessage(chatId: Long, languageCode: String) {
        telegramService.sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = i18nService.getMessage("command.help.message", languageCode),
        )
    }
}