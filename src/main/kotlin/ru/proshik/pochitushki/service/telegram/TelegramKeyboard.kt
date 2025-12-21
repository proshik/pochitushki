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
        const val CALLBACK_DELETE_POST = "delete_button"

        const val CALLBACK_UNREAD_POST = "unread_button"
        const val CALLBACK_DELETE_ARCHIVE_POST = "delete_archive_button"

        const val CALLBACK_NEXT_POSTS = "next_posts"
        const val CALLBACK_PREVIOUS_POSTS = "previous_posts"

        const val CALLBACK_NEXT_ARCHIVE_POSTS = "next_archive_posts"
        const val CALLBACK_PREVIOUS_ARCHIVE_POSTS = "previous_archive_posts"

        const val CALLBACK_PROFILE_FEED_SETTINGS = "profile_feed_settings"
        const val CALLBACK_PROFILE_LANGUAGE_SETTINGS = "profile_language_settings"

        const val CALLBACK_PROFILE_FEED_SETTINGS_CHANGE = "profile_feed_settings_changed"
        const val CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE = "profile_language_settings_changed"
        const val CALLBACK_PROFILE_BACK_SETTINGS = "profile_back_button_settings"
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

    fun buildFeedPostInlineKeyboard(postId: Long, postType: PostType, languageCode: String): InlineKeyboardMarkup {
        val (unreadOrArchive, delete) = when (postType) {
            PostType.UNREAD -> {
                Pair(
                    Pair(i18nService.getMessage("command.feed.button.archive", languageCode), CALLBACK_ARCHIVE_POST),
                    Pair(i18nService.getMessage("command.feed.button.delete", languageCode), CALLBACK_DELETE_POST)
                )
            }

            PostType.ARCHIVE -> {
                Pair(
                    Pair(i18nService.getMessage("command.feed.button.unread", languageCode), CALLBACK_UNREAD_POST),
                    Pair(i18nService.getMessage("command.feed.button.delete", languageCode), CALLBACK_DELETE_ARCHIVE_POST)
                )
            }
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
            )
        )
    }

    /**
     * Build navigation InlineKeyboard.
     *
     * If offset=0, then print just "Next" button.
     * If offset > 0 and (offset + postSize) > countOfPosts, then print "Next" and "Previous" button.
     * if offset > 0 and (offset + postSize) = countOfPosts, then print just "Previous" button.
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

    /**
     * Create main keyboard.
     */
//    fun mainKeyboard(): List<List<KeyboardButton>> {
//        return listOf(
//            listOf(KeyboardButton(R_BUTTON_FEED_EN)),
//            listOf(KeyboardButton(R_BUTTON_RANDOM_POST_EN), KeyboardButton(R_BUTTON_ARCHIVE_EN)),
//            listOf(KeyboardButton(R_BUTTON_PROFILE_EN)),
//        )
//    }
}