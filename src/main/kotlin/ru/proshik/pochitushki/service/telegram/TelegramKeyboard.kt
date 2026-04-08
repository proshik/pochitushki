package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.TelegramService.Companion.LANGUAGE_EN_CODE
import ru.proshik.pochitushki.service.TelegramService.Companion.LANGUAGE_RU_CODE

@Service
class TelegramKeyboard(private val i18nService: I18nService) {

    companion object {
        const val CALLBACK_ARCHIVE_POST = "archive_button"
        const val CALLBACK_UNREAD_POST = "unread_button"

        const val CALLBACK_DELETE_POST = "delete_button"
        const val CALLBACK_DELETE_ARCHIVE_POST = "delete_archive_button"

        const val CALLBACK_NEXT_POSTS = "next_posts"
        const val CALLBACK_PREVIOUS_POSTS = "previous_posts"

        const val CALLBACK_RANDOM_POST_ARCHIVE = "random_archive_button"
        const val CALLBACK_RANDOM_POST_DELETE = "random_delete_button"
        const val CALLBACK_NEXT_RANDOM_POST = "next_random_post"

        const val CALLBACK_NEXT_ARCHIVE_POSTS = "next_archive_posts"
        const val CALLBACK_PREVIOUS_ARCHIVE_POSTS = "previous_archive_posts"

        const val CALLBACK_TOGGLE_UNREAD_FAVORITE = "toggle_unread_favorite"
        const val CALLBACK_TOGGLE_ARCHIVE_FAVORITE = "toggle_archive_favorite"
        const val CALLBACK_TOGGLE_RANDOM_FAVORITE = "toggle_random_favorite"

        const val CALLBACK_NEXT_FAVORITES = "next_favorites"
        const val CALLBACK_PREVIOUS_FAVORITES = "previous_favorites"

        const val CALLBACK_PDF_UNREAD_POST = "pdf_unread_post"
        const val CALLBACK_PDF_ARCHIVE_POST = "pdf_archive_post"
        const val CALLBACK_PDF_ENGINE = "pdf_engine"

        const val CALLBACK_PROFILE_FEED_SETTINGS = "profile_feed_settings"
        const val CALLBACK_PROFILE_LANGUAGE_SETTINGS = "profile_language_settings"

        const val CALLBACK_PROFILE_FEED_SETTINGS_CHANGE = "profile_feed_settings_changed"
        const val CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE = "profile_language_settings_changed"
        const val CALLBACK_PROFILE_BACK_SETTINGS = "profile_back_button_settings"

        const val CALLBACK_FAVORITES_TO_ARCHIVE = "favorites_to_archive"
        const val CALLBACK_FAVORITES_TO_UNREAD = "favorites_to_unread"

        const val CALLBACK_TOGGLE_FAVORITES_UNREAD_FAVORITE = "toggle_favorites_unread_favorite"
        const val CALLBACK_TOGGLE_FAVORITES_ARCHIVE_FAVORITE = "toggle_favorites_archive_favorite"
    }

    /**
     * Select the interface and message language from options
     * [ Русский ]
     * [ English 🇬🇧]
     */
    fun buildSettingsLanguageKeyboard(languageCode: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup.create(
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = i18nService.getMessage("command.profile.language.russian", languageCode),
                    callbackData = "$CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE|$LANGUAGE_RU_CODE"
                ),
                InlineKeyboardButton.CallbackData(
                    text = i18nService.getMessage("command.profile.language.english", languageCode),
                    callbackData = "$CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE|$LANGUAGE_EN_CODE"
                )
            ),
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = i18nService.getMessage("command.profile.language.back", languageCode),
                    callbackData = "$CALLBACK_PROFILE_BACK_SETTINGS|_"
                )
            )
        )
    }

    /**
     * Feed settings keyboard:
     *
     * [ 1 ][ 2 ][ 3 ][ 4 ][ 5 ]
     */
    fun buildSettingsFeedKeyboard(feedEntriesNumber: Int, languageCode: String): InlineKeyboardMarkup {
        val countPostButtons = (1..5).map {
            val (text, value) = if (it == feedEntriesNumber) {
                Pair("$it ✔\uFE0F", it)
            } else {
                Pair(it.toString(), it)
            }
            InlineKeyboardButton.CallbackData(
                text = text,
                callbackData = "$CALLBACK_PROFILE_FEED_SETTINGS_CHANGE|$value"
            )
        }

        return InlineKeyboardMarkup.create(
            countPostButtons,
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = i18nService.getMessage("command.profile.language.back", languageCode),
                    callbackData = "$CALLBACK_PROFILE_BACK_SETTINGS|_"
                )
            )
        )
    }

    /**
     * Keyboard Profile command:
     *
     * [ Feed settings ]
     * [ Language settings ]
     */
    fun buildProfileSettingsKeyboard(languageCode: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup.create(
            listOf(
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.profile.settings.feed", languageCode),
                        callbackData = "$CALLBACK_PROFILE_FEED_SETTINGS|_"
                    ),
                ),
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.profile.settings.language", languageCode),
                        callbackData = "$CALLBACK_PROFILE_LANGUAGE_SETTINGS|_"
                    ),
                ),
            )
        )
    }

    /**
     * Keyboard for PDF engine selection:
     *
     * [ OpenHTML ] [ Playwright ]
     */
    fun buildPdfEngineKeyboard(
        postId: Long,
        postType: PostType,
        availableEngines: List<String>,
        languageCode: String
    ): InlineKeyboardMarkup {
        val postTypeCode = when (postType) {
            PostType.UNREAD, PostType.FAVORITES -> "u"
            PostType.ARCHIVE -> "a"
        }

        val buttons = availableEngines.map { engine ->
            val label = i18nService.getMessage("command.pdf.engine.$engine", languageCode)
            InlineKeyboardButton.CallbackData(
                text = label,
                callbackData = "$CALLBACK_PDF_ENGINE|${postId}_${postTypeCode}_$engine"
            )
        }

        return InlineKeyboardMarkup.create(listOf(buttons))
    }

    /**
     * Keyboard for random post:
     *
     * [ Archive ] [ Delete ]
     * [ ⭐ Favorite / ★ Unfavorite ] [ 📄 PDF ]
     * [  Next Random Post  ]
     */
    fun buildRandomPostInlineKeyboard(postId: Long, isFavorite: Boolean, languageCode: String): InlineKeyboardMarkup {
        val favoriteText = if (isFavorite) {
            i18nService.getMessage("command.feed.button.unfavorite", languageCode)
        } else {
            i18nService.getMessage("command.feed.button.favorite", languageCode)
        }

        return InlineKeyboardMarkup.create(
            listOf(
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.feed.button.archive", languageCode),
                        callbackData = "$CALLBACK_RANDOM_POST_ARCHIVE|$postId"
                    ),
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.feed.button.delete.unread", languageCode),
                        callbackData = "$CALLBACK_RANDOM_POST_DELETE|$postId"
                    )
                ),
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = favoriteText,
                        callbackData = "$CALLBACK_TOGGLE_RANDOM_FAVORITE|$postId"
                    ),
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.feed.button.pdf", languageCode),
                        callbackData = "$CALLBACK_PDF_UNREAD_POST|$postId"
                    ),
                ),
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.random_post.button.next_random_post", languageCode),
                        callbackData = "$CALLBACK_NEXT_RANDOM_POST|_"
                    ),
                )
            )
        )
    }

    /**
     * Keyboard for item of UNREAD, ARCHIVE, and FAVORITES posts:
     *
     * [ Archive / Unread ] [ Delete ]
     * [ ⭐ Favorite / ★ Unfavorite ] [ 📄 PDF ]
     */
    fun buildFeedPostInlineKeyboard(
        postId: Long,
        postType: PostType,
        isFavorite: Boolean,
        languageCode: String,
        isArchived: Boolean = false
    ): InlineKeyboardMarkup {
        val (unreadOrArchive, delete) = when {
            postType == PostType.FAVORITES && !isArchived -> {
                Pair(
                    Pair(i18nService.getMessage("command.feed.button.archive", languageCode), CALLBACK_FAVORITES_TO_ARCHIVE),
                    Pair(i18nService.getMessage("command.feed.button.delete.unread", languageCode), CALLBACK_DELETE_POST)
                )
            }
            postType == PostType.FAVORITES && isArchived -> {
                Pair(
                    Pair(i18nService.getMessage("command.feed.button.unread", languageCode), CALLBACK_FAVORITES_TO_UNREAD),
                    Pair(i18nService.getMessage("command.feed.button.delete.archive", languageCode), CALLBACK_DELETE_ARCHIVE_POST)
                )
            }
            postType == PostType.UNREAD -> {
                Pair(
                    Pair(i18nService.getMessage("command.feed.button.archive", languageCode), CALLBACK_ARCHIVE_POST),
                    Pair(i18nService.getMessage("command.feed.button.delete.unread", languageCode), CALLBACK_DELETE_POST)
                )
            }
            else -> {
                Pair(
                    Pair(i18nService.getMessage("command.feed.button.unread", languageCode), CALLBACK_UNREAD_POST),
                    Pair(i18nService.getMessage("command.feed.button.delete.archive", languageCode), CALLBACK_DELETE_ARCHIVE_POST)
                )
            }
        }

        val favoriteCallback = when {
            postType == PostType.FAVORITES && isArchived -> CALLBACK_TOGGLE_FAVORITES_ARCHIVE_FAVORITE
            postType == PostType.FAVORITES -> CALLBACK_TOGGLE_FAVORITES_UNREAD_FAVORITE
            postType == PostType.ARCHIVE -> CALLBACK_TOGGLE_ARCHIVE_FAVORITE
            else -> CALLBACK_TOGGLE_UNREAD_FAVORITE
        }
        val favoriteText = if (isFavorite) {
            i18nService.getMessage("command.feed.button.unfavorite", languageCode)
        } else {
            i18nService.getMessage("command.feed.button.favorite", languageCode)
        }

        val pdfCallback = when {
            postType == PostType.ARCHIVE || (postType == PostType.FAVORITES && isArchived) -> CALLBACK_PDF_ARCHIVE_POST
            else -> CALLBACK_PDF_UNREAD_POST
        }

        return InlineKeyboardMarkup.create(
            listOf(
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = unreadOrArchive.first,
                        callbackData = "${unreadOrArchive.second}|$postId"
                    ),
                    InlineKeyboardButton.CallbackData(
                        text = delete.first,
                        callbackData = "${delete.second}|$postId"
                    )
                ),
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = favoriteText,
                        callbackData = "$favoriteCallback|$postId"
                    ),
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.feed.button.pdf", languageCode),
                        callbackData = "$pdfCallback|$postId"
                    ),
                ),
            )
        )
    }

    /**
     * Build navigation InlineKeyboard.
     *
     * If offset=0, then print just "Next" button.
     * If offset > 0 and (offset + postSize) > countOfPosts, then print "Next" and "Previous" button.
     * if offset > 0 and (offset + postSize) = countOfPosts, then print just "Previous" button.
     *
     * [ Previous ][ Next ]
     */
    fun buildNavigationKeyboard(
        postType: PostType,
        postSize: Int,
        offset: Int,
        countOfPosts: Int,
        languageCode: String,
        postCount: Int,
    ): ReplyMarkup? {
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

            PostType.FAVORITES -> {
                previousCallbackAction = CALLBACK_PREVIOUS_FAVORITES
                nextCallbackAction = CALLBACK_NEXT_FAVORITES
            }
        }

        val navigationKeyboard = if (offset == 0 && postSize < countOfPosts) {
            InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            text = i18nService.getMessage("command.feed.button.next", languageCode),
                            callbackData = "${nextCallbackAction}|${postCount}"
                        ),
                    ),
                )
            )
        } else if (offset > 0 && ((offset + postSize) < countOfPosts)) {
            InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            text = i18nService.getMessage("command.feed.button.previous", languageCode),
                            callbackData = "${previousCallbackAction}|${offset - postCount}"
                        ),
                        InlineKeyboardButton.CallbackData(
                            text = i18nService.getMessage("command.feed.button.next", languageCode),
                            callbackData = "${nextCallbackAction}|${offset + postCount}"
                        ),
                    ),
                )
            )
        } else if (offset > 0 && ((offset + postSize) == countOfPosts)) {
            InlineKeyboardMarkup.create(
                listOf(
                    listOf(
                        InlineKeyboardButton.CallbackData(
                            text = i18nService.getMessage("command.feed.button.previous", languageCode),
                            callbackData = "${previousCallbackAction}|${offset - postCount}"
                        ),
                    ),
                )
            )
        } else {
            null
        }

        return navigationKeyboard
    }
}
