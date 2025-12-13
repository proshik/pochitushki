package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_ARCHIVE_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_BACK_TO_MAIN_MENU_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_BACK_TO_PROFILE_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_EXPORT_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_FEED_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_FEED_SETTINGS_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_IMPORT_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_LANGUAGE_SETTINGS_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_NEXT
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_PREV
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_PROFILE_EN
import ru.proshik.pochitushki.service.telegram.CommandHandler.Companion.R_BUTTON_RANDOM_POST_EN

@Service
class TelegramKeyboard {

    companion object {
        const val CALLBACK_ARCHIVE_POST = "archive_button"
        const val CALLBACK_DELETE_POST = "delete_button"

        const val CALLBACK_UNREAD_POST = "unread_button"
        const val CALLBACK_DELETE_ARCHIVE_POST = "delete_archive_button"

        const val CALLBACK_NEXT_POSTS = "next_posts"
        const val CALLBACK_PREVIOUS_POSTS = "previous_posts"

        const val CALLBACK_NEXT_ARCHIVE_POSTS = "next_archive_posts"
        const val CALLBACK_PREVIOUS_ARCHIVE_POSTS = "previous_archive_posts"
    }

    fun nextReplayKeyboard(): List<List<KeyboardButton>> {
        return listOf(
            listOf(KeyboardButton(R_BUTTON_NEXT)),
        )
    }

    fun nextPrevReplayKeyboard(): List<List<KeyboardButton>> {
        return listOf(
            listOf(KeyboardButton(R_BUTTON_NEXT), KeyboardButton(R_BUTTON_PREV)),
        )
    }

    fun prevReplayKeyboard(): List<List<KeyboardButton>> {
        return listOf(
            listOf(KeyboardButton(R_BUTTON_PREV)),
        )
    }

    /**
     * Create main keyboard.
     */
    fun mainKeyboard(): List<List<KeyboardButton>> {
        return listOf(
            listOf(KeyboardButton(R_BUTTON_FEED_EN)),
            listOf(KeyboardButton(R_BUTTON_RANDOM_POST_EN), KeyboardButton(R_BUTTON_ARCHIVE_EN)),
            listOf(KeyboardButton(R_BUTTON_PROFILE_EN)),
        )
    }

    /**
     * Create profile keyboard.
     */
    fun profileKeyboard(): List<List<KeyboardButton>> {
        return listOf(
//            listOf(KeyboardButton(R_BUTTON_SETTINGS_EN)),
            listOf(KeyboardButton(R_BUTTON_IMPORT_EN), KeyboardButton(R_BUTTON_EXPORT_EN)),
            listOf(KeyboardButton(R_BUTTON_BACK_TO_MAIN_MENU_EN))
        )
    }

    /**
     * Create settings keyboard.
     */
    fun settingsKeyboard(): List<List<KeyboardButton>> {
        return listOf(
            listOf(KeyboardButton(R_BUTTON_LANGUAGE_SETTINGS_EN)),
            listOf(KeyboardButton(R_BUTTON_FEED_SETTINGS_EN)),
            listOf(KeyboardButton(R_BUTTON_BACK_TO_PROFILE_EN))
        )
    }
}