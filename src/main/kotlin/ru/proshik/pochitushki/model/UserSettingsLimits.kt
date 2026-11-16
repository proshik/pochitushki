package ru.proshik.pochitushki.model

/**
 * What the settings actually accept.
 *
 * `languageCode` picks the resource bundle for the bot and the site. It arrives from two
 * untrusted places — the web API body and Telegram `callback_data` — so the allowed set
 * lives here once instead of being re-derived (or forgotten) at each entry point.
 */
object UserSettingsLimits {

    const val LANGUAGE_RU = "ru"
    const val LANGUAGE_EN = "en"

    val SUPPORTED_LANGUAGES = listOf(LANGUAGE_EN, LANGUAGE_RU)
}
