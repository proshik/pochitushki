package ru.proshik.pochitushki.service

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.ReplyMarkup
import com.github.kotlintelegrambot.entities.ReplyParameters
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.types.TelegramBotResult
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.UserSettingsData
import ru.proshik.pochitushki.model.UserStoreData
import ru.proshik.pochitushki.service.telegram.BotTaskExecutor
import ru.proshik.pochitushki.service.metrics.AppMetrics
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard
import ru.proshik.pochitushki.service.telegram.api.delivered
import ru.proshik.pochitushki.service.telegram.card.PostCardService

@Service
@EnableConfigurationProperties(value = [TelegramProperties::class])
class TelegramService(
    private val botProvider: BotProvider,
    private val postService: PostService,
    private val userService: UserService,
    private val importService: ImportService,
    private val exportService: ExportService,
    private val i18nService: I18nService,
    private val telegramKeyboard: TelegramKeyboard,
    private val postCardService: PostCardService,
    private val botTaskExecutor: BotTaskExecutor,
    private val rateLimitService: RateLimitService,
    private val metrics: AppMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val LANGUAGE_RU_CODE = "ru"
        const val LANGUAGE_EN_CODE = "en"

        val supportedLanguages: List<String> = listOf(LANGUAGE_EN_CODE, LANGUAGE_RU_CODE)
    }

    /**
     * Send a message
     */
    fun sendMessage(chatId: Long, text: String): TelegramBotResult<Message> {
        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            text = text,
        )
        handleTgErrorResponse(result, chatId)
        return result
    }

    /**
     * Send a message with replay keyboard
     */
    fun sendMessageWithReplayKeyboard(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
        replyToMessageId: Long? = null,
    ): TelegramBotResult<Message> {
        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            text = text,
            replyMarkup = replyMarkup,
            replyParameters = replyToMessageId?.let { ReplyParameters(messageId = it) },
        )
        handleTgErrorResponse(result, chatId)
        return result
    }

    /**
     * Send a message with keyboard
     */
    fun sendMessageWithKeyboard(chatId: Long, text: String, keyboard: ReplyMarkup): TelegramBotResult<Message> {
        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            text = text,
            replyMarkup = keyboard
        )
        handleTgErrorResponse(result, chatId)
        return result
    }

    /**
     * Edit message
     */
    fun editMessage(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: ReplyMarkup,
        parseMode: ParseMode? = null
    ) {
        // TODO handle warning
        botProvider.getBot().editMessageText(
            chatId = ChatId.Id(chatId),
            messageId = messageId,
            text = text,
            replyMarkup = keyboard,
            parseMode = parseMode,
        )
    }

    /**
     * Handle Telegram error responses.
     */
    private fun handleTgErrorResponse(tgBotResult: TelegramBotResult<Message>, chatId: Long) {
        tgBotResult.fold(
            { logger.info("success send message: chatId={}", chatId) },
            { logger.warn("unexpected error send message: chatId={}, err={}", chatId, it) }
        )
    }

    fun showErrorMessage(chatId: Long) {
        logger.info("showErrorMessage: chatId={}", chatId)

        val user = userService.getUserByChatId(chatId)

        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            text = i18nService.getMessage("command.feed.add_post.error", user.settings.languageCode),
        )
        handleTgErrorResponse(result, chatId)

        logger.info("showErrorMessage success: chatId={}", chatId)
    }

    /**
     * Add post to unread feed
     */
    fun addPost(chatId: Long, messageId: Long, rawUrl: String) {
        logger.debug("addPost: chatId={}, messageId={}, rawUrl={}", chatId, messageId, rawUrl)

        val user = userService.getUserByChatId(chatId)

        val url = try {
            URI.create(rawUrl).toURL()
        } catch (e: Exception) {
            // IllegalArgumentException for text that is no URI at all, MalformedURLException for a
            // URI of a scheme Java has no URL handler for (doi:, tg://, magnet:). Both are the
            // user's text, not our failure.
            if (e !is IllegalArgumentException && e !is MalformedURLException) throw e
            logger.debug("could not parse rawUrl={}, message={}", rawUrl, e.message)

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                text = i18nService.getMessage("command.feed.unrecognized_url", user.settings.languageCode),
            )
            handleTgErrorResponse(result, chatId)

            return
        }

        val storedPost = postService.findPost(user.id, PostType.UNREAD, url)
        if (storedPost.isEmpty()) {
            logger.debug("post not found: chatId={}, url={}", chatId, url)

            val (storedPostId, _) = try {
                postService.addPost(url, user.id)
            } catch (e: SsrfValidationException) {
                logger.debug("blocked SSRF url from bot: chatId={}, url={}", chatId, url)
                val result = botProvider.getBot().sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = i18nService.getMessage("command.feed.unrecognized_url", user.settings.languageCode),
                )
                handleTgErrorResponse(result, chatId)
                return
            }

            val saved = postService.getPost(storedPostId, user.id, PostType.UNREAD) ?: run {
                logger.warn("addPost: just saved post {} not found", storedPostId)
                metrics.operationFailed(AppMetrics.Operation.SAVE_POST)
                return
            }
            postCardService.sendSaved(chatId, saved, alreadySaved = false, replyTo = messageId)
        } else {
            logger.debug("post already added: chatId={}, url={}", chatId, url)

            // One answer, not one per row: the lookup is an exact match now, and any
            // historical duplicates of the same URL would otherwise each get a card.
            postCardService.sendSaved(chatId, storedPost.first(), alreadySaved = true, replyTo = messageId)
        }

        logger.info("addPost success: chatId={}, messageId={}, rawUrl={}", chatId, messageId, rawUrl)
    }

    /**
     * Add new user
     */
    fun addUser(chatId: Long, username: String?, firstName: String?, lastName: String?, languageCode: String): UserSettingsData {
        logger.debug("addUser: chatId={}, username={}", chatId, username)

        val userData = userService.findUserByChatId(chatId)
        val userSettings = if (userData == null) {
            val userSettings = UserSettingsData(languageCode = languageCode)
            val userStoreData = UserStoreData(chatId, username, firstName, lastName, userSettings)
            userService.addUser(userStoreData)

            logger.info("addUser success: chatId={}, username={}", chatId, username)

            userSettings
        } else {
            logger.info("user already created for chatId=$chatId")

            userData.settings
        }

        return userSettings
    }

    fun import(chatId: Long, fileId: String) {
        logger.debug("importData: chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        if (!rateLimitService.tryAcquire(user.id, RateLimitService.Action.IMPORT)) {
            sendMessage(chatId, i18nService.getMessage("command.rate_limited", user.settings.languageCode))
            return
        }

        val byteArray = botProvider.getBot().downloadFileBytes(fileId)
        if (byteArray == null) {
            metrics.operationFailed(AppMetrics.Operation.IMPORT)
            sendMessage(chatId, i18nService.getMessage("command.import.download_error", user.settings.languageCode))
            return
        }

        val file = File.createTempFile("pocket_import_${user.id}_", ".zip")
        FileUtils.writeByteArrayToFile(file, byteArray)

        val languageCode = user.settings.languageCode
        val userId = user.id
        // Off the dispatcher thread: an import parses up to MAX_POSTS rows and writes them in
        // one transaction, and every other user's commands queue behind it otherwise.
        val accepted = botTaskExecutor.submit("import userId=$userId") {
            try {
                importService.importZipArchive(userId, file)
                sendMessage(chatId, i18nService.getMessage("command.import.success", languageCode))
            } catch (ex: Exception) {
                metrics.operationFailed(AppMetrics.Operation.IMPORT)
                logger.warn("import file error: userId={}", userId, ex)
                sendMessage(chatId, i18nService.getMessage("command.import.error", languageCode))
            } finally {
                FileUtils.delete(file)
            }
        }
        if (!accepted) {
            metrics.operationFailed(AppMetrics.Operation.IMPORT, AppMetrics.Reason.BUSY)
            FileUtils.delete(file)
            sendMessage(chatId, i18nService.getMessage("command.busy", languageCode))
        }

        logger.info("importData accepted: chatId={}", chatId)
    }

    fun showImportInstruction(chatId: Long) {
        logger.debug("showImportInstruction: chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        sendMessage(chatId, i18nService.getMessage("command.profile.import.instruction", user.settings.languageCode))

        logger.info("showImportInstruction success: chatId={}", chatId)
    }

    fun export(chatId: Long, messageId: Long) {
        logger.debug("export chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val languageCode = user.settings.languageCode
        val userId = user.id

        // Export reads every post the user owns and zips it — same reason as import.
        val accepted = botTaskExecutor.submit("export userId=$userId") {
            // Inside a catch like the send below: otherwise a failed export reached only
            // BotTaskExecutor's log, and the user waited for a file that never came.
            val file = try {
                exportService.export(userId)
            } catch (e: Exception) {
                metrics.operationFailed(AppMetrics.Operation.EXPORT)
                logger.warn("export error: chatId={}", chatId, e)
                sendMessage(chatId, i18nService.getMessage("command.export.error", languageCode))
                return@submit
            }
            if (file == null) {
                sendMessage(chatId, i18nService.getMessage("command.export.nothing_to_export", languageCode))
                return@submit
            }

            try {
                val bytes = file.readBytes()
                val sent = botProvider.getBot().sendDocument(
                    chatId = ChatId.fromId(chatId),
                    document = TelegramFile.ByByteArray(bytes, file.name),
                )
                check(sent.delivered()) { "telegram refused the export file" }
                logger.info("export success: chatId={}", chatId)
            } catch (e: Exception) {
                metrics.operationFailed(AppMetrics.Operation.EXPORT)
                logger.warn("export send error: chatId={}", chatId, e)
                sendMessage(chatId, i18nService.getMessage("command.export.error", languageCode))
            } finally {
                file.delete()
            }
        }
        if (!accepted) {
            metrics.operationFailed(AppMetrics.Operation.EXPORT, AppMetrics.Reason.BUSY)
            sendMessage(chatId, i18nService.getMessage("command.busy", languageCode))
        }
    }

    fun getLanguageSettings(chatId: Long, messageId: Long) {
        logger.debug("getLanguageSettings: chatId={}, messageId={}", chatId, messageId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        // TODO handle warning
        editMessage(
            chatId = chatId,
            messageId = messageId,
            text = i18nService.getMessage("command.profile.settings.language.message", user.settings.languageCode),
            keyboard = telegramKeyboard.buildSettingsLanguageKeyboard(user.settings.languageCode)
        )

        logger.debug("getLanguageSettings success: chatId={}, messageId={}", chatId, messageId)
    }

    fun getUserSettings(chatId: Long): UserSettingsData {
        logger.debug("getUserSettings: chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        return user.settings.also {
            logger.info("getUserSettings success: chatId={}", chatId)
        }
    }

    fun showProfile(chatId: Long, messageId: Long) {
        logger.debug("showProfile: chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val languageCode = user.settings.languageCode

        sendMessageWithReplayKeyboard(
            chatId = chatId,
            text = i18nService.getMessage("command.profile.message", user.settings.languageCode),
            replyToMessageId = messageId,
            replyMarkup = telegramKeyboard.buildProfileSettingsKeyboard(languageCode)
        )
    }

    fun updateUserSettingsLanguageCode(chatId: Long, messageId: Long, languageCode: String) {
        logger.debug("updateUserSettingsLanguageCode chatId={}, messageId={}, languageCode={}", chatId, messageId, languageCode)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("user could not be found for chatId=$chatId")

        val updatedUserSettings = user.settings.copy(languageCode = languageCode)

        userService.updateUserSettings(user.id, updatedUserSettings)

        editMessage(
            chatId = chatId,
            messageId = messageId,
            text = i18nService.getMessage("command.profile.settings.language.message", languageCode),
            keyboard = telegramKeyboard.buildSettingsLanguageKeyboard(languageCode)
        )

        logger.info("updateUserSettingsLanguageCode success: chatId={}, messageId={}, languageCode={}", chatId, messageId, languageCode)
    }
}