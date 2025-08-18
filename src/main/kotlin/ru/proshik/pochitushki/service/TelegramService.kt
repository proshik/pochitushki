package ru.proshik.pochitushki.service

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.types.TelegramBotResult
import java.io.File
import java.net.URI
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.configuration.BotProvider
import ru.proshik.pochitushki.configuration.properties.TelegramProperties
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.UserStoreData
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_ARCHIVE_POST
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_DELETE_ARCHIVE_POST
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_DELETE_POST
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_NEXT_ARCHIVE_POSTS
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_NEXT_POSTS
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_PREVIOUS_ARCHIVE_POSTS
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_PREVIOUS_POSTS
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard.Companion.CALLBACK_UNREAD_POST

@Service
@EnableConfigurationProperties(value = [TelegramProperties::class])
class TelegramService(
    private val botProvider: BotProvider,
    private val postService: PostService,
    private val userService: UserService,
    private val importService: ImportService,
    private val exportService: ExportService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val POST_COUNT = 3

        const val NAVIGATION_TEXT_BUTTON =
            """
                \-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-\-
            """
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
        replyMarkup: KeyboardReplyMarkup? = null,
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
     * Send a message with inline keyboard
     */
    fun sendMessageWithInlineKeyboard(chatId: Long, text: String, inlineKeyboard: InlineKeyboardMarkup): TelegramBotResult<Message> {
        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            text = text,
            replyMarkup = inlineKeyboard
        )
        handleTgErrorResponse(result, chatId)
        return result
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

    fun addPost(chatId: Long, messageId: Long, rawUrl: String) {
        val url = try {
            URI.create(rawUrl).toURL()
        } catch (e: IllegalArgumentException) {
            logger.debug("could not parse rawUrl={}, message={}", rawUrl, e.message)

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                text = "URL was not recognized and the post could not be saved \uD83D\uDEAB",
            )
            handleTgErrorResponse(result, chatId)

            return
        }

        val user = userService.getUserByChatId(chatId)

        val storedPostTitle = postService.addPost(url, user.id)

        val message = if (storedPostTitle != null) {
            "Post has been saved ✅: \"$storedPostTitle\""
        } else {
            "Post has been saved ✅"
        }

        val result = botProvider.getBot().sendMessage(
            chatId = ChatId.fromId(chatId),
            text = message,
            replyToMessageId = messageId
        )
        handleTgErrorResponse(result, chatId)
    }

    fun addUser(chatId: Long, username: String?, firstName: String?, lastName: String?) {
        val userData = userService.findUserByChatId(chatId)
        if (userData == null) {
            val userStoreData = UserStoreData(chatId, username, firstName, lastName)
            userService.addUser(userStoreData)

            return
        }
        logger.info("user already created for chatId=$chatId")
    }

    fun archivePost(chatId: Long, messageId: Long, postId: Long) {
        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("archivePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.archivePost(postId)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )
    }

    fun unreadPost(chatId: Long, messageId: Long, postId: Long) {
        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("unreadPost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.unreadPost(postId)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )
    }

    fun deletePost(chatId: Long, messageId: Long, postId: Long, postType: PostType) {
        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("deletePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.deletePost(postId, postType)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )
    }

    fun getFeed(chatId: Long, messageId: Long, offset: Int = 0) {
        val userData = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val posts = postService.getPosts(userData.id, PostType.UNREAD, offset)
        if (posts.isEmpty()) {
            sendMessageWithReplayKeyboard(
                chatId = chatId,
                text = "You don't have any added posts yet \uD83D\uDE14\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F",
                replyToMessageId = messageId
            )

            return
        }

        val lastPosts = posts.take(POST_COUNT)

        val message = lastPosts
            .map { post ->
                val message = buildPostMessage(post.url, post.title)
                val keyboard = buildFeedPostInlineKeyboard(post.id)

                PostFeedItem(message, keyboard)
            }

        // в цикле выводим N сообщений с кнопками "Archive" и "Delete"
        message.forEach { postItem ->
            sendPostMessage(chatId = chatId, postItem = postItem)
        }

        sendNavigationKeyboard(chatId, PostType.UNREAD, posts.size, offset)
    }

    fun getRandomPost(chatId: Long, messageId: Long) {
        logger.debug("getRandomPost chatId={}, messageId={}", chatId, messageId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val randomPost = postService.getRandomPost(user.id)
        if (randomPost == null) {
            sendMessageWithReplayKeyboard(
                chatId = chatId,
                text = "You don't have any added posts yet \uD83D\uDE14\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F",
                replyToMessageId = messageId
            )

            return
        }

        val postItem = PostFeedItem(
            message = buildPostMessage(randomPost.url, randomPost.title),
            keyboard = buildFeedPostInlineKeyboard(randomPost.id)
        )

        sendPostMessage(chatId, messageId, postItem)
    }

    fun getArchive(chatId: Long, messageId: Long, offset: Int = 0) {
        logger.debug("getArchive chatId={}, messageId={}", chatId, messageId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data by chatId=$chatId")

        val archivedPosts = postService.getPosts(user.id, PostType.ARCHIVE, offset)
        if (archivedPosts.isEmpty()) {
            sendMessageWithReplayKeyboard(
                chatId = chatId,
                text = "You don't have archived posts yet \uD83D\uDE14\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F",
                replyToMessageId = messageId
            )

            return
        }

        val lastPosts = archivedPosts.take(POST_COUNT)

        val message = lastPosts
            .map { post ->
                val message = buildPostMessage(post.url, post.title)
                val keyboard = buildArchivePostInlineKeyboard(post.id)

                PostFeedItem(message, keyboard)
            }

        // в цикле выводим N сообщений с кнопками "Unread" и "Delete"
        message.forEach { postItem ->
            sendPostMessage(chatId = chatId, postItem = postItem)
        }

        sendNavigationKeyboard(chatId, PostType.UNREAD, archivedPosts.size, offset)
    }

    fun import(chatId: Long, fileId: String) {
        logger.debug("importData chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val byteArray = botProvider.getBot().downloadFileBytes(fileId)
        if (byteArray == null) {
            sendMessage(chatId, "Can't download file: chatId=$chatId")
            return
        }

        val file = File("/tmp/pocket_import_${user.id}.zip")
        FileUtils.writeByteArrayToFile(file, byteArray)

        try {
            importService.importZipArchive(user.id, file)
        } catch (ex: Exception) {
            logger.warn("import file error: userId={}", user.id, ex)
            sendMessage(chatId, "Error on import! Please try again.")
        } finally {
            FileUtils.delete(file)
        }

        sendMessage(chatId, "Success import!")

        logger.debug("importData chatId={}", chatId)
    }

    fun export(chatId: Long, messageId: Long) {
        logger.debug("export chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        exportService.export(user.id)
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

    private fun buildPostMessage(postUrl: String, postTitle: String?): String {
        val escapedUrl = escapeTextMarkdown2(postUrl)
        val escapedTitle = postTitle?.let { title -> escapeTextMarkdown2(title) } ?: escapedUrl

        val message = "[${escapedTitle}]($escapedUrl)"

        return message
    }

    private fun buildFeedPostInlineKeyboard(postId: Long): InlineKeyboardMarkup = InlineKeyboardMarkup.create(
        listOf(
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = "Archive \uD83D\uDDC4",
                    callbackData = "${CALLBACK_ARCHIVE_POST}|$postId"
                ),
                InlineKeyboardButton.CallbackData(
                    text = "Delete ❌",
                    callbackData = "${CALLBACK_DELETE_POST}|$postId"
                )
            ),
        )
    )

    private fun buildArchivePostInlineKeyboard(postId: Long): InlineKeyboardMarkup = InlineKeyboardMarkup.create(
        listOf(
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = "Unread \uD83D\uDDC4",
                    callbackData = "${CALLBACK_UNREAD_POST}|$postId"
                ),
                InlineKeyboardButton.CallbackData(
                    text = "Delete ❌",
                    callbackData = "${CALLBACK_DELETE_ARCHIVE_POST}|$postId"
                )
            ),
        )
    )

    private fun sendNavigationKeyboard(chatId: Long, postType: PostType, postSize: Int, offset: Int) {
        var previousCallbackAction: String
        var nextCallbackAction: String

        when (postType) {
            PostType.UNREAD -> {
                previousCallbackAction = CALLBACK_PREVIOUS_POSTS
                nextCallbackAction = CALLBACK_NEXT_POSTS
            }

            PostType.ARCHIVE -> {
                previousCallbackAction = CALLBACK_NEXT_ARCHIVE_POSTS
                nextCallbackAction = CALLBACK_PREVIOUS_ARCHIVE_POSTS
            }
        }

        if (postSize > POST_COUNT && offset == 0) {
            val pagingKeyboard = InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(text = "Next ➡\uFE0F", callbackData = "${nextCallbackAction}|${POST_COUNT}"),
                    ),
                )
            )

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                parseMode = ParseMode.MARKDOWN_V2,
                text = NAVIGATION_TEXT_BUTTON,
                replyMarkup = pagingKeyboard
            )
            handleTgErrorResponse(result, chatId)
        } else if (postSize >= POST_COUNT && offset >= POST_COUNT) {
            val pagingKeyboard = InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            text = "⬅\uFE0F Previous",
                            callbackData = "${previousCallbackAction}|${offset - POST_COUNT}"
                        ),
                        InlineKeyboardButton.CallbackData(
                            text = "Next ➡\uFE0F",
                            callbackData = "${nextCallbackAction}|${offset + POST_COUNT}"
                        ),
                    ),
                )
            )

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                parseMode = ParseMode.MARKDOWN_V2,
                text = NAVIGATION_TEXT_BUTTON,
                replyMarkup = pagingKeyboard
            )
            handleTgErrorResponse(result, chatId)
        } else if (postSize < POST_COUNT && offset >= POST_COUNT) {
            val pagingKeyboard = InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            text = "⬅\uFE0F Previous",
                            callbackData = "${previousCallbackAction}|${offset - POST_COUNT}"
                        ),
                    ),
                )
            )

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                parseMode = ParseMode.MARKDOWN_V2,
                text = NAVIGATION_TEXT_BUTTON,
                replyMarkup = pagingKeyboard
            )
            handleTgErrorResponse(result, chatId)
        }
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

//                val croppedTitle = if (escapedTitle.length < 80 ){
//                    escapedTitle += " ".repeat(79 - escapedTitle.length) + "."
//                    escapedTitle
//                } else if (escapedTitle.length > 80){
//                    escapedTitle.take(77) + " ..."
//                } else {
//                    escapedTitle
//                }