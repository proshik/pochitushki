package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.Update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.TelegramService

/**
 * Handler for Telegram commands and text messages.
 * This class handles both command-based interactions (like "/start" and "/logout")
 * and text-based interactions (like button presses).
 */
@Component
class CommandHandler(
    private val telegramService: TelegramService,
    private val telegramKeyboard: TelegramKeyboard
) : TelegramUpdateHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val COMMAND_START = "start"
        const val COMMAND_HELP = "help"
        const val COMMAND_FEED = "feed"
        const val COMMAND_ARCHIVE = "archive"
        const val COMMAND_RANDOM_POST = "random_post"
        const val COMMAND_PROFILE = "profile"

        const val R_BUTTON_FEED_EN = "Feed"
        const val R_BUTTON_FEED_RU = "Лента"

        const val R_BUTTON_NEXT = "Next"
        const val R_BUTTON_PREV = "Prev"

        const val R_BUTTON_RANDOM_POST_EN = "Random post"
        const val R_BUTTON_RANDOM_POST_RU = "Случайный пост"

        const val R_BUTTON_ARCHIVE_EN = "Archive"
        const val R_BUTTON_ARCHIVE_RU = "Архив"

        const val R_BUTTON_PROFILE_EN = "Profile"
        const val R_BUTTON_PROFILE_RU = "Профиль"

        const val R_BUTTON_SETTINGS_EN = "Settings"
        const val R_BUTTON_SETTINGS_RU = "Настройки"

        const val R_BUTTON_IMPORT_EN = "Import"
        const val R_BUTTON_IMPORT_RU = "Импорт"

        const val R_BUTTON_EXPORT_EN = "Export"
        const val R_BUTTON_EXPORT_RU = "Экспорт"

        const val R_BUTTON_LANGUAGE_SETTINGS_EN = "Language settings"
        const val R_BUTTON_LANGUAGE_SETTINGS_RU = "Настойки языка"

        const val R_BUTTON_FEED_SETTINGS_EN = "Feed settings"
        const val R_BUTTON_FEED_SETTINGS_RU = "Настройки ленты"

        const val R_BUTTON_BACK_TO_MAIN_MENU_EN = "◀\uFE0F Main menu"
        const val R_BUTTON_BACK_TO_MAIN_MENU_RU = "◀\uFE0F Основное меню"

        const val R_BUTTON_BACK_TO_PROFILE_EN = "◀\uFE0F Profile"
        const val R_BUTTON_BACK_TO_PROFILE_RU = "◀\uFE0F Профиль пользователя"

        val COMMANDS = listOf(
            "/$COMMAND_START",
            "/$COMMAND_HELP",
            "/$COMMAND_FEED",
            "/$COMMAND_ARCHIVE",
            "/$COMMAND_RANDOM_POST",
            "/$COMMAND_PROFILE",
        )

        val BUTTONS = listOf(
            R_BUTTON_FEED_EN,
            R_BUTTON_FEED_RU,
            R_BUTTON_RANDOM_POST_EN,
            R_BUTTON_RANDOM_POST_RU,
            R_BUTTON_ARCHIVE_EN,
            R_BUTTON_ARCHIVE_RU,
            R_BUTTON_PROFILE_EN,
            R_BUTTON_PROFILE_RU,
            R_BUTTON_SETTINGS_EN,
            R_BUTTON_SETTINGS_RU,
            R_BUTTON_IMPORT_EN,
            R_BUTTON_IMPORT_RU,
            R_BUTTON_EXPORT_EN,
            R_BUTTON_EXPORT_RU,
            R_BUTTON_LANGUAGE_SETTINGS_EN,
            R_BUTTON_LANGUAGE_SETTINGS_RU,
            R_BUTTON_FEED_SETTINGS_EN,
            R_BUTTON_FEED_SETTINGS_RU,
            R_BUTTON_BACK_TO_MAIN_MENU_EN,
            R_BUTTON_BACK_TO_MAIN_MENU_RU,
            R_BUTTON_BACK_TO_PROFILE_EN,
            R_BUTTON_BACK_TO_PROFILE_RU
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


//        dispatcher.command(COMMAND_FEED) { handleFeedOperation(message)}
//        dispatcher.command(COMMAND_ARCHIVE) { handleArchivePostOperation(message)}
//        dispatcher.command(COMMAND_RANDOM_POST) { handleRandomPostOperation(message)}
//        dispatcher.command(COMMAND_PROFILE) { handleProfileOperations(message)}

        // Register text handlers
//        dispatcher.text(text = R_BUTTON_FEED_EN) { handleFeedOperation(message) }
//        dispatcher.text(text = R_BUTTON_FEED_RU) { handleFeedOperation(message) }
//
//        dispatcher.text(text = R_BUTTON_RANDOM_POST_EN) { handleRandomPostOperation(message) }
//        dispatcher.text(text = R_BUTTON_RANDOM_POST_RU) { handleRandomPostOperation(message) }
//
//        dispatcher.text(text = R_BUTTON_ARCHIVE_EN) { handleArchivePostOperation(message) }
//        dispatcher.text(text = R_BUTTON_ARCHIVE_RU) { handleArchivePostOperation(message) }
//
//        dispatcher.text(text = R_BUTTON_PROFILE_EN) { handleProfileOperations(message) }
//        dispatcher.text(text = R_BUTTON_PROFILE_RU) { handleProfileOperations(message) }
//
//        dispatcher.text(text = R_BUTTON_IMPORT_EN) { handleImportButton(message) }
//        dispatcher.text(text = R_BUTTON_IMPORT_RU) { handleImportButton(message) }

//        dispatcher.text(text = R_BUTTON_EXPORT_EN) { handleExportButton(message) }
//        dispatcher.text(text = R_BUTTON_EXPORT_RU) { handleExportButton(message) }

//        dispatcher.text(text = R_BUTTON_BACK_TO_MAIN_MENU_EN) { handleBackToMainMenuButton(message) }
//        dispatcher.text(text = R_BUTTON_BACK_TO_MAIN_MENU_RU) { handleBackToMainMenuButton(message) }

        dispatcher.message { handleFileUpload(message) }
    }

    private fun handleStartCommand(update: Update) {
        if (update.message == null) {
            throw RuntimeException("start command message shouldn't be null: updateId=${update.updateId}")
        }

        telegramService.addUser(
            update.message!!.chat.id,
            update.message?.from?.username,
            update.message?.from?.firstName,
            update.message?.from?.lastName,
        )

        val chatId = update.message!!.chat.id

        startWelcomeMessage(chatId)
    }

    private fun handleHelpCommand(update: Update) {
        if (update.message == null) {
            throw RuntimeException("start command message shouldn't be null: updateId=${update.updateId}")
        }

        val chatId = update.message!!.chat.id

        helpMessage(chatId)
    }

    private fun handleFeedOperation(message: Message) {
        telegramService.getFeed(message.chat.id, message.messageId, postType = PostType.UNREAD)
    }

    private fun handleRandomPostOperation(message: Message) {
        telegramService.getRandomPost(message.chat.id, message.messageId)
    }

    private fun handleArchivePostOperation(message: Message) {
        telegramService.getFeed(message.chat.id, message.messageId, postType = PostType.ARCHIVE)
    }

    private fun handleProfileOperations(message: Message) {
        val chatId = message.chat.id

        telegramService.sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = "User Profile",
//            replyMarkup = KeyboardReplyMarkup(
//                keyboard = telegramKeyboard.profileKeyboard(),
//                resizeKeyboard = true
//            ),
            replyToMessageId = message.messageId
        )
    }

    private fun handleImportButton(message: Message) {
        val chatId = message.chat.id

        telegramService.sendMessage(
            chatId = chatId,
            text = "Waiting an archive from https://getpocket.com/export in *.zip archive with *.csv files inside",
        )
    }

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

    private fun handleBackToMainMenuButton(message: Message) {
        val chatId = message.chat.id

        telegramService.sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = "Main menu",
//            replyMarkup = KeyboardReplyMarkup(
//                keyboard = telegramKeyboard.mainKeyboard(),
//                resizeKeyboard = true
//            ),
            replyToMessageId = message.messageId
        )
    }

    private fun handleCommonText(message: Message) {
        if (message.text != null && (message.text in COMMANDS || message.text in BUTTONS)) {
            return
        }

        if (message.text != null) {
            telegramService.addPost(message.chat.id, message.messageId, message.text!!)

            return
        }

        logger.debug("unknown message type: chatId={}", message.chat.id)
    }

//    private fun handleBackButton(message: Message) {
//        val chatId = message.chat.id
//
//        val user = telegramService.findAuthenticatedUserByChatId(chatId)
//        if (user != null) {
//            telegramService.sendMessage(
//                chatId = chatId,
//                text = "Выберите раздел для подсчёта статистики или настройки профиля!",
//                replyMarkup = KeyboardReplyMarkup(
//                    keyboard = mainKeyboard(),
//                    resizeKeyboard = true
//                )
//            )
//        } else {
//            greetingsMessage(chatId)
//        }
//    }

    private fun startWelcomeMessage(chatId: Long) {
        telegramService.sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = """
                Welcome! 👋

                I'm your personal bot for saving and reading web pages later. Just send me a link to get started! 📥
            """.trimIndent(),
//            replyMarkup = KeyboardReplyMarkup(keyboard = telegramKeyboard.mainKeyboard(), resizeKeyboard = true)
        )
    }

    private fun helpMessage(chatId: Long) {
        telegramService.sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = """
                Here's how I can help you:

                To save a link: Just send me the URL.
                To view your saved links: Use the command, /feed button
                Press `Archive` button to move post in Archive. Press `Delete` button to delete post from feed.
                You can get random post: Use the command /random_post
                Tow view archived posts use the command /archive

                Need more help? Feel free to ask @proshik!
            """.trimIndent(),
//            replyMarkup = KeyboardReplyMarkup(keyboard = telegramKeyboard.mainKeyboard(), resizeKeyboard = true)
        )
    }
}