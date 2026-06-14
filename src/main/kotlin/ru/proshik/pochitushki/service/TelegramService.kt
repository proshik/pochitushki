package ru.proshik.pochitushki.service

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.ReplyMarkup
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.types.TelegramBotResult
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.UserSettingsData
import ru.proshik.pochitushki.model.UserStoreData
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard

@Service
@EnableConfigurationProperties(value = [TelegramProperties::class])
class TelegramService(
    private val botProvider: BotProvider,
    private val postService: PostService,
    private val userService: UserService,
    private val importService: ImportService,
    private val exportService: ExportService,
    private val pdfGenerators: Map<String, PdfGenerator>,
    private val i18nService: I18nService,
    private val telegramKeyboard: TelegramKeyboard,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_POST_COUNT = 3

        const val NAVIGATION_TEXT_BUTTON =
            """
                \-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-
            """

        const val LANGUAGE_RU_CODE = "ru"
        const val LANGUAGE_EN_CODE = "en"

        val supportedLanguages: List<String> = listOf(LANGUAGE_EN_CODE, LANGUAGE_RU_CODE)
    }

    data class PostFeedItem(
        val message: String,
        val keyboard: InlineKeyboardMarkup
    )

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
            replyToMessageId = replyToMessageId
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
        disableWebPagePreview: Boolean? = null,
        parseMode: ParseMode? = null
    ) {
        // TODO handle warning
        botProvider.getBot().editMessageText(
            chatId = ChatId.Id(chatId),
            messageId = messageId,
            text = text,
            replyMarkup = keyboard,
            disableWebPagePreview = disableWebPagePreview,
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
        } catch (e: IllegalArgumentException) {
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

            val (storedPostId, storedPostTitle) = try {
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

            val textMessage = i18nService.getMessage("command.feed.add_post", user.settings.languageCode)
            val message = if (storedPostTitle != null) {
                "$textMessage\n\n$storedPostTitle"
            } else {
                textMessage
            }

            val keyboard = telegramKeyboard.buildFeedPostInlineKeyboard(storedPostId, PostType.UNREAD, false, user.settings.languageCode)

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                text = message,
                replyToMessageId = messageId,
                replyMarkup = keyboard
            )
            handleTgErrorResponse(result, chatId)
        } else {
            logger.debug("post already added: chatId={}, url={}", chatId, url)

            storedPost.forEach { post ->
                val message = buildPostMessage(
                    url.toString(),
                    post.title,
                    i18nService.getMessage("command.feed.post_already_added", user.settings.languageCode)
                )
                val keyboard = telegramKeyboard.buildFeedPostInlineKeyboard(post.id, PostType.UNREAD, post.isFavorite, user.settings.languageCode)

                val postFeedItem = PostFeedItem(message, keyboard)

                sendPostMessage(chatId = chatId, postItem = postFeedItem)
            }
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
            val userSettings = UserSettingsData(languageCode, DEFAULT_POST_COUNT)
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

    fun toArchivePost(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("toArchivePost: chatId={}, postId={}", chatId, postId)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("toArchivePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.archivePost(postId, user.id)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )

        logger.info("toArchivePost success: chatId={}, postId={}", chatId, postId)
    }

    fun archivePost(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("archivePost: chatId={}, postId={}", chatId, postId)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("archivePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.archivePost(postId, user.id)

        logger.info("archivePost success: chatId={}, postId={}", chatId, postId)
    }

    fun toUnreadPost(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("toUnreadPost: chatId={}, postId={}", chatId, postId)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("toUnreadPost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.unreadPost(postId, user.id)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )

        logger.info("toUnreadPost success: chatId={}, postId={}", chatId, postId)
    }

    fun favoritesToArchive(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("favoritesToArchive: chatId={}, postId={}", chatId, postId)
        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        val post = postService.getPost(postId, user.id, PostType.UNREAD) ?: run {
            logger.warn("favoritesToArchive: post not found postId={}", postId)
            return
        }
        val newArchiveId = postService.archivePost(postId, user.id)
        val messageText = buildPostMessage(post.url, post.title)
        val keyboard = telegramKeyboard.buildFeedPostInlineKeyboard(newArchiveId, PostType.FAVORITES, post.isFavorite, user.settings.languageCode, isArchived = true)
        editMessage(chatId, messageId, messageText, keyboard, disableWebPagePreview = false, parseMode = ParseMode.MARKDOWN_V2)
        logger.info("favoritesToArchive success: chatId={}, postId={}, newArchiveId={}", chatId, postId, newArchiveId)
    }

    fun favoritesToUnread(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("favoritesToUnread: chatId={}, postId={}", chatId, postId)
        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        val post = postService.getPost(postId, user.id, PostType.ARCHIVE) ?: run {
            logger.warn("favoritesToUnread: post not found postId={}", postId)
            return
        }
        val newUnreadId = postService.unreadPost(postId, user.id)
        val messageText = buildPostMessage(post.url, post.title)
        val keyboard = telegramKeyboard.buildFeedPostInlineKeyboard(newUnreadId, PostType.FAVORITES, post.isFavorite, user.settings.languageCode, isArchived = false)
        editMessage(chatId, messageId, messageText, keyboard, disableWebPagePreview = false, parseMode = ParseMode.MARKDOWN_V2)
        logger.info("favoritesToUnread success: chatId={}, postId={}, newUnreadId={}", chatId, postId, newUnreadId)
    }

    fun toDeletePost(chatId: Long, messageId: Long, postId: Long, postType: PostType) {
        logger.debug("toDeletePost: chatId={}, postId={}, postType={}", chatId, postId, postType)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("toDeletePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.deletePost(postId, user.id, postType)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )

        logger.info("toDeletePost success: chatId={}, postId={}", chatId, postId)
    }

    fun deletePost(chatId: Long, messageId: Long, postId: Long, postType: PostType) {
        logger.debug("deletePost: chatId={}, postId={}, postType={}", chatId, postId, postType)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("deletePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.deletePost(postId, user.id, postType)

        logger.info("deletePost success: chatId={}, postId={}, postType={}", chatId, postId, postType)
    }

    fun getFeed(chatId: Long, messageId: Long, offset: Int = 0, postType: PostType) {
        logger.debug("getFeed: chatId={}, offset={}", chatId, offset)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val languageCode = user.settings.languageCode

        val countOfPosts = postService.getPostCount(user.id, postType)

        val posts = postService.getPosts(user.id, postType, user.settings.tgFeedEntriesNumber, offset)
        if (posts.isEmpty()) {
            val notFoundCode = when (postType) {
                PostType.FAVORITES -> "command.feed.not_found_favorites"
                else -> "command.feed.not_found_post"
            }
            sendMessageWithReplayKeyboard(
                chatId = chatId,
                text = i18nService.getMessage(notFoundCode, languageCode),
                replyToMessageId = messageId
            )

            return
        }

        val message = posts
            .map { post ->
                val date = if (postType == PostType.UNREAD || postType == PostType.ARCHIVE) post.createdDate else null
                val message = buildPostMessage(post.url, post.title, createdDate = date)
                val keyboard = if (postType == PostType.FAVORITES) {
                    telegramKeyboard.buildFeedPostInlineKeyboard(post.id, PostType.FAVORITES, post.isFavorite, languageCode, isArchived = post.isArchived)
                } else {
                    telegramKeyboard.buildFeedPostInlineKeyboard(post.id, postType, post.isFavorite, languageCode)
                }

                PostFeedItem(message, keyboard)
            }

        if (offset == 0) {
            val messageCode = when (postType) {
                PostType.UNREAD -> "command.feed.unread_message"
                PostType.ARCHIVE -> "command.feed.archive_message"
                PostType.FAVORITES -> "command.favorites.message"
                PostType.ALL -> error("ALL is not supported in Telegram bot")
            }

            val text = i18nService.getMessage(messageCode, languageCode, arrayOf(countOfPosts))

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                text = text,
            )
            handleTgErrorResponse(result, chatId)
        }

        // in loop print N messages with buttons "Archive" и "Delete"
        message.forEach { postItem ->
            sendPostMessage(chatId = chatId, postItem = postItem)
        }

        val navigationKeyboard =
            telegramKeyboard.buildNavigationKeyboard(
                postType,
                posts.size,
                offset,
                countOfPosts,
                languageCode,
                user.settings.tgFeedEntriesNumber
            )
        if (navigationKeyboard != null) {
            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                parseMode = ParseMode.MARKDOWN_V2,
                text = NAVIGATION_TEXT_BUTTON,
                replyMarkup = navigationKeyboard
            )
            handleTgErrorResponse(result, chatId)
        }

        logger.info("getFeed success: chatId={}, offset={}", chatId, offset)
    }

    fun getRandomPost(chatId: Long, messageId: Long, isEditMessage: Boolean = false) {
        logger.debug("getRandomPost: chatId={}, messageId={}", chatId, messageId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val randomPost = postService.getRandomPost(user.id)
        if (randomPost == null) {
            sendMessageWithReplayKeyboard(
                chatId = chatId,
                text = i18nService.getMessage("command.random_post.not_found", user.settings.languageCode),
                replyToMessageId = messageId
            )

            return
        }

        val postItem = PostFeedItem(
            message = buildPostMessage(randomPost.url, randomPost.title),
            keyboard = telegramKeyboard.buildRandomPostInlineKeyboard(randomPost.id, randomPost.isFavorite, user.settings.languageCode)
        )

        if (isEditMessage) {
            editMessage(
                chatId = chatId,
                messageId = messageId,
                disableWebPagePreview = false,
                parseMode = ParseMode.MARKDOWN_V2,
                text = postItem.message,
                keyboard = postItem.keyboard
            )
        } else {
            sendPostMessage(chatId, messageId, postItem)
        }

        logger.info("getRandomPost success: chatId={}, messageId={}", chatId, messageId)
    }

    fun toggleFavorite(chatId: Long, messageId: Long, postId: Long, postType: PostType) {
        logger.debug("toggleFavorite: chatId={}, postId={}, postType={}", chatId, postId, postType)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val newIsFavorite = postService.toggleFavorite(postId, user.id, postType)
        val post = postService.getPost(postId, user.id, postType)
        if (post == null) {
            logger.warn("toggleFavorite: post not found postId={}", postId)
            return
        }

        val messageText = buildPostMessage(post.url, post.title)
        val keyboard = telegramKeyboard.buildFeedPostInlineKeyboard(postId, postType, newIsFavorite, user.settings.languageCode)

        editMessage(
            chatId = chatId,
            messageId = messageId,
            text = messageText,
            keyboard = keyboard,
            disableWebPagePreview = false,
            parseMode = ParseMode.MARKDOWN_V2
        )

        logger.info("toggleFavorite success: chatId={}, postId={}, newIsFavorite={}", chatId, postId, newIsFavorite)
    }

    fun toggleFavoriteFromFavorites(chatId: Long, messageId: Long, postId: Long, postType: PostType) {
        logger.debug("toggleFavoriteFromFavorites: chatId={}, postId={}, postType={}", chatId, postId, postType)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        val newIsFavorite = postService.toggleFavorite(postId, user.id, postType)

        if (!newIsFavorite) {
            // Post removed from favorites — delete the card from the list
            botProvider.getBot().deleteMessage(chatId = ChatId.fromId(chatId), messageId = messageId)
        } else {
            // Re-added to favorites (edge case) — update keyboard in place
            val post = postService.getPost(postId, user.id, postType) ?: run {
                logger.warn("toggleFavoriteFromFavorites: post not found postId={}", postId)
                return
            }
            val isArchived = postType == PostType.ARCHIVE
            val keyboard = telegramKeyboard.buildFeedPostInlineKeyboard(postId, PostType.FAVORITES, newIsFavorite, user.settings.languageCode, isArchived = isArchived)
            editMessage(chatId, messageId, buildPostMessage(post.url, post.title), keyboard, disableWebPagePreview = false, parseMode = ParseMode.MARKDOWN_V2)
        }

        logger.info("toggleFavoriteFromFavorites success: chatId={}, postId={}, newIsFavorite={}", chatId, postId, newIsFavorite)
    }

    fun toggleFavoriteForRandomPost(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("toggleFavoriteForRandomPost: chatId={}, postId={}", chatId, postId)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val newIsFavorite = postService.toggleFavorite(postId, user.id, PostType.UNREAD)
        val post = postService.getPost(postId, user.id, PostType.UNREAD)
        if (post == null) {
            logger.warn("toggleFavoriteForRandomPost: post not found postId={}", postId)
            return
        }

        val messageText = buildPostMessage(post.url, post.title)
        val keyboard = telegramKeyboard.buildRandomPostInlineKeyboard(postId, newIsFavorite, user.settings.languageCode)

        editMessage(
            chatId = chatId,
            messageId = messageId,
            text = messageText,
            keyboard = keyboard,
            disableWebPagePreview = false,
            parseMode = ParseMode.MARKDOWN_V2
        )

        logger.info("toggleFavoriteForRandomPost success: chatId={}, postId={}, newIsFavorite={}", chatId, postId, newIsFavorite)
    }

    fun getAvailablePdfEngines(): List<String> {
        return pdfGenerators.keys
            .map { it.removeSuffix("PdfGenerator") }
            .sorted()
    }

    fun showPdfEngineSelection(chatId: Long, postId: Long, postType: PostType) {
        logger.debug("showPdfEngineSelection: chatId={}, postId={}, postType={}", chatId, postId, postType)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val engines = getAvailablePdfEngines()
        if (engines.size == 1) {
            sendPostPdf(chatId, postId, postType, engines.first())
            return
        }

        val keyboard = telegramKeyboard.buildPdfEngineKeyboard(postId, postType, engines, user.settings.languageCode)
        sendMessageWithKeyboard(
            chatId = chatId,
            text = i18nService.getMessage("command.pdf.select_engine", user.settings.languageCode),
            keyboard = keyboard
        )
    }

    fun sendPostPdf(chatId: Long, postId: Long, postType: PostType, engine: String) {
        logger.debug("sendPostPdf: chatId={}, postId={}, postType={}, engine={}", chatId, postId, postType, engine)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val post = postService.getPost(postId, user.id, postType)
        if (post == null) {
            logger.warn("sendPostPdf: post not found postId={}", postId)
            return
        }

        val generator = pdfGenerators["${engine}PdfGenerator"]
        if (generator == null) {
            logger.warn("sendPostPdf: unknown engine={}", engine)
            sendMessage(chatId, i18nService.getMessage("command.pdf.error", user.settings.languageCode))
            return
        }

        try {
            val pdfBytes = generator.generatePdf(post.url)
            val filename = generatePdfFilename(post.title, post.url)

            botProvider.getBot().sendDocument(
                chatId = ChatId.fromId(chatId),
                document = TelegramFile.ByByteArray(pdfBytes, filename),
                caption = post.title ?: post.url
            )

            logger.info("sendPostPdf success: chatId={}, postId={}, engine={}", chatId, postId, engine)
        } catch (e: Exception) {
            logger.warn("sendPostPdf error: postId={}, url={}, engine={}", postId, post.url, engine, e)
            sendMessage(chatId, i18nService.getMessage("command.pdf.error", user.settings.languageCode))
        }
    }

    private fun generatePdfFilename(title: String?, url: String): String {
        val name = title
            ?.take(50)
            ?.replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s-]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "_")
            ?.ifEmpty { null }
            ?: URI.create(url).host?.replace(".", "_")
            ?: "document"
        return "$name.pdf"
    }

    fun import(chatId: Long, fileId: String) {
        logger.debug("importData: chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val byteArray = botProvider.getBot().downloadFileBytes(fileId)
        if (byteArray == null) {
            sendMessage(chatId, i18nService.getMessage("command.import.download_error", user.settings.languageCode))
            return
        }

        val file = File.createTempFile("pocket_import_${user.id}_", ".zip")
        FileUtils.writeByteArrayToFile(file, byteArray)

        try {
            importService.importZipArchive(user.id, file)
            sendMessage(chatId, i18nService.getMessage("command.import.success", user.settings.languageCode))
        } catch (ex: Exception) {
            logger.warn("import file error: userId={}", user.id, ex)
            sendMessage(chatId, i18nService.getMessage("command.import.error", user.settings.languageCode))
        } finally {
            FileUtils.delete(file)
        }

        logger.info("importData success: chatId={}", chatId)
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

        val file = exportService.export(user.id)
        if (file == null) {
            sendMessage(chatId, i18nService.getMessage("command.export.nothing_to_export", user.settings.languageCode))
            return
        }

        try {
            val bytes = file.readBytes()
            botProvider.getBot().sendDocument(
                chatId = ChatId.fromId(chatId),
                document = TelegramFile.ByByteArray(bytes, file.name),
            )
            logger.info("export success: chatId={}", chatId)
        } catch (e: Exception) {
            logger.warn("export send error: chatId={}", chatId, e)
            sendMessage(chatId, i18nService.getMessage("command.export.error", user.settings.languageCode))
        } finally {
            file.delete()
        }
    }

    fun getFeedSettings(chatId: Long, messageId: Long) {
        logger.debug("getFeedSettings: chatId={}, messageId={}", chatId, messageId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val languageCode = user.settings.languageCode

        // TODO handle warning
        editMessage(
            chatId = chatId,
            messageId = messageId,
            text = i18nService.getMessage("command.profile.settings.feed.message", languageCode),
            keyboard = telegramKeyboard.buildSettingsFeedKeyboard(user.settings.tgFeedEntriesNumber, languageCode)
        )

        logger.debug("getFeedSettings success: chatId={}, messageId={}", chatId, messageId)
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

    fun updateUserSettingsFeedCount(chatId: Long, messageId: Long, tgFeedEntriesNumber: Int) {
        logger.debug("updateUserSettingsFeedCount chatId={}, messageId={}, languageCode={}", chatId, messageId, tgFeedEntriesNumber)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("user could not be found for chatId=$chatId")

        val updatedUserSettings = user.settings.copy(tgFeedEntriesNumber = tgFeedEntriesNumber)

        userService.updateUserSettings(user.id, updatedUserSettings)

        editMessage(
            chatId = chatId,
            messageId = messageId,
            text = i18nService.getMessage("command.profile.settings.feed.message", user.settings.languageCode),
            keyboard = telegramKeyboard.buildSettingsFeedKeyboard(tgFeedEntriesNumber, user.settings.languageCode)
        )

        logger.info("updateUserSettingsFeedCount success: chatId={}, tgFeedEntriesNumber={}", chatId, tgFeedEntriesNumber)
    }

    private fun buildPostMessage(postUrl: String, postTitle: String?, prefixMessage: String? = "", createdDate: LocalDateTime? = null): String {
        val escapedUrl = escapeTextMarkdown2(postUrl)
        val escapedTitle = postTitle?.let { title -> escapeTextMarkdown2(title) } ?: escapedUrl
        val dateLine = createdDate?.let { date ->
            "\n_" + escapeTextMarkdown2(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) + "_"
        } ?: ""

        val message = "$prefixMessage[${escapedTitle}]($escapedUrl)$dateLine"

        return message
    }

    private fun sendPostMessage(chatId: Long, messageId: Long? = null, postItem: PostFeedItem) {
        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            disableWebPagePreview = false,
            parseMode = ParseMode.MARKDOWN_V2,
            text = postItem.message,
            replyMarkup = postItem.keyboard,
            replyToMessageId = messageId
        )
        handleTgErrorResponse(result, chatId)
    }

    private fun escapeTextMarkdown2(text: String): String =
        text
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!")
}