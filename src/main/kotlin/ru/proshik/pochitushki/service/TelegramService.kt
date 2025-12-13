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
        logger.debug("addPost: chatId={}, messageId={}, rawUrl={}", chatId, messageId, rawUrl)

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

        val storedPost = postService.findPost(user.id, PostType.UNREAD, url)
        if (storedPost == null) {
            logger.debug("post not found: chatId={}, url={}", chatId, url)

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

        } else {
            logger.debug("post already added: chatId={}, url={}", chatId, url)

            val message =
                buildPostMessage(url.toString(), storedPost.title, "Post already added early ✊: ")
            val keyboard = buildFeedPostInlineKeyboard(storedPost.id, PostType.UNREAD)

            val postFeedItem = PostFeedItem(message, keyboard)

            sendPostMessage(chatId = chatId, postItem = postFeedItem)
        }
    }

    fun addUser(chatId: Long, username: String?, firstName: String?, lastName: String?) {
        logger.debug("addUser: chatId={}, username={}", chatId, username)

        val userData = userService.findUserByChatId(chatId)
        if (userData == null) {
            val userStoreData = UserStoreData(chatId, username, firstName, lastName)
            userService.addUser(userStoreData)

            logger.info("addUser success: chatId={}, username={}", chatId, username)

            return
        }

        logger.info("user already created for chatId=$chatId")
    }

    fun archivePost(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("archivePost: chatId={}, postId={}", chatId, postId)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("archivePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.archivePost(postId)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )

        logger.info("archivePost success: chatId={}, postId={}", chatId, postId)
    }

    fun unreadPost(chatId: Long, messageId: Long, postId: Long) {
        logger.debug("unreadPost: chatId={}, postId={}", chatId, postId)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("unreadPost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.unreadPost(postId)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )

        logger.info("unreadPost success: chatId={}, postId={}", chatId, postId)
    }

    fun deletePost(chatId: Long, messageId: Long, postId: Long, postType: PostType) {
        logger.debug("deletePost: chatId={}, postId={}, postType={}", chatId, postId, postType)

        val user = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")
        logger.debug("deletePost for userId={}, messageId={}, postId={}", user.id, messageId, postId)

        postService.deletePost(postId, postType)

        botProvider.getBot().deleteMessage(
            chatId = ChatId.fromId(chatId),
            messageId = messageId
        )

        logger.info("deletePost success: chatId={}, postId={}", chatId, postId)
    }

    fun getFeed(chatId: Long, messageId: Long, offset: Int = 0, postType: PostType) {
        logger.debug("getFeed: chatId={}, offset={}", chatId, offset)

        val userData = userService.findUserByChatId(chatId) ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        val countOfPosts = postService.getPostCount(userData.id, postType)

        val posts = postService.getPosts(userData.id, postType, POST_COUNT, offset)
        if (posts.isEmpty()) {
            sendMessageWithReplayKeyboard(
                chatId = chatId,
                text = "You don't have any added posts yet \uD83D\uDE14\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F\uFE0F",
                replyToMessageId = messageId
            )

            return
        }

        val message = posts
            .map { post ->
                val message = buildPostMessage(post.url, post.title)
                val keyboard = buildFeedPostInlineKeyboard(post.id, postType)

                PostFeedItem(message, keyboard)
            }

        if (offset == 0) {
            val text = "Count of ${postType.value} posts: $countOfPosts \uD83D\uDD04"

            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                text = text,
            )
            handleTgErrorResponse(result, chatId)
        }

        // в цикле выводим N сообщений с кнопками "Archive" и "Delete"
        message.forEach { postItem ->
            sendPostMessage(chatId = chatId, postItem = postItem)
        }

        sendNavigationKeyboard(chatId, postType, posts.size, offset, countOfPosts)

        logger.info("getFeed success: chatId={}, offset={}", chatId, offset)
    }

    fun getRandomPost(chatId: Long, messageId: Long) {
        logger.debug("getRandomPost: chatId={}, messageId={}", chatId, messageId)

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
            keyboard = buildFeedPostInlineKeyboard(randomPost.id, PostType.UNREAD)
        )

        sendPostMessage(chatId, messageId, postItem)

        logger.info("getRandomPost success: chatId={}, messageId={}", chatId, messageId)
    }

    fun import(chatId: Long, fileId: String) {
        logger.debug("importData: chatId={}", chatId)

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

        logger.info("importData success: chatId={}", chatId)
    }

    fun export(chatId: Long, messageId: Long) {
        logger.debug("export chatId={}", chatId)

        val user = userService.findUserByChatId(chatId)
            ?: throw RuntimeException("Can't find user data for chatId=$chatId")

        exportService.export(user.id)

        logger.info("export success: chatId={}", chatId)
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

    private fun buildPostMessage(postUrl: String, postTitle: String?, prefixMessage: String? = ""): String {
        val escapedUrl = escapeTextMarkdown2(postUrl)
        val escapedTitle = postTitle?.let { title -> escapeTextMarkdown2(title) } ?: escapedUrl

        val message = "$prefixMessage[${escapedTitle}]($escapedUrl)"

        return message
    }

    private fun buildFeedPostInlineKeyboard(postId: Long, postType: PostType): InlineKeyboardMarkup {
        return when (postType) {
            PostType.UNREAD -> {
                InlineKeyboardMarkup.create(
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
            }

            PostType.ARCHIVE -> {
                InlineKeyboardMarkup.create(
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
            }
        }
    }

    /**
     * Build navigation InlineKeyboard.
     *
     * If offset=0, then print just "Next" button.
     * If offset > 0 and (offset + postSize) > countOfPosts, then print "Next" and "Previous" button.
     * if offset > 0 and (offset + postSize) = countOfPosts, then print just "Previous" button.
     */
    private fun sendNavigationKeyboard(chatId: Long, postType: PostType, postSize: Int, offset: Int, countOfPosts: Int) {
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

        val navigationKeyboard = if (offset == 0 && postSize < countOfPosts) {
            InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(text = "Next ➡\uFE0F", callbackData = "${nextCallbackAction}|${POST_COUNT}"),
                    ),
                )
            )
        } else if (offset > 0 && ((offset + postSize) < countOfPosts)) {
            InlineKeyboardMarkup.create(
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
        } else if (offset > 0 && ((offset + postSize) == countOfPosts)) {
            InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            text = "⬅\uFE0F Previous",
                            callbackData = "${previousCallbackAction}|${offset - POST_COUNT}"
                        ),
                    ),
                )
            )
        } else {
            null
        }

        if (navigationKeyboard != null) {
            val result = botProvider.getBot().sendMessage(
                chatId = ChatId.fromId(chatId),
                parseMode = ParseMode.MARKDOWN_V2,
                text = NAVIGATION_TEXT_BUTTON,
                replyMarkup = navigationKeyboard
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