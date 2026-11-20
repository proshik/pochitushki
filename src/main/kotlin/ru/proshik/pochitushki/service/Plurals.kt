package ru.proshik.pochitushki.service

import java.util.Locale
import org.springframework.context.MessageSource

/**
 * Plural forms for the message bundles: a count picks the key suffix, the bundle holds the text.
 *
 * Russian has three forms — `one` (1, 21, 101), `few` (2–4, 22–24) and `many` (0, 5–20, 11–14,
 * 25…); English has `one` and `other`. Only the languages the site speaks
 * (UserSettingsLimits.SUPPORTED_LANGUAGES); anything else gets the English rule.
 */
object Plurals {

    fun category(n: Long, locale: Locale): String {
        val abs = kotlin.math.abs(n)
        if (locale.language != "ru") return if (abs == 1L) "one" else "other"
        val mod10 = abs % 10
        val mod100 = abs % 100
        return when {
            mod10 == 1L && mod100 != 11L -> "one"
            mod10 in 2L..4L && mod100 !in 12L..14L -> "few"
            else -> "many"
        }
    }
}

/** `code.one|few|many|other` for [n], with [n] as `{0}`. */
fun MessageSource.plural(code: String, n: Long, locale: Locale): String =
    getMessage("$code.${Plurals.category(n, locale)}", arrayOf(n.toString()), locale)
