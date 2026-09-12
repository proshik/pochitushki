package ru.proshik.pochitushki.model

/**
 * What the settings actually accept.
 *
 * Both values reach the bot: `tgFeedEntriesNumber` decides how many messages one `/feed`
 * sends, and `languageCode` picks the resource bundle. They arrive from two untrusted
 * places — the web API body and Telegram `callback_data` — so the allowed set lives here
 * once instead of being re-derived (or forgotten) at each entry point.
 */
object UserSettingsLimits {

    /** Matches the buttons TelegramKeyboard.buildSettingsFeedKeyboard draws. */
    val FEED_ENTRIES_RANGE = 1..5

    const val LANGUAGE_RU = "ru"
    const val LANGUAGE_EN = "en"

    val SUPPORTED_LANGUAGES = listOf(LANGUAGE_EN, LANGUAGE_RU)
}
