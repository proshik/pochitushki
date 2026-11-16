package ru.proshik.pochitushki.service.telegram

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.TelegramService.Companion.LANGUAGE_EN_CODE
import ru.proshik.pochitushki.service.TelegramService.Companion.LANGUAGE_RU_CODE
import ru.proshik.pochitushki.service.telegram.api.InlineButton
import ru.proshik.pochitushki.service.telegram.api.InlineKeyboard
import ru.proshik.pochitushki.service.telegram.card.CardAction
import ru.proshik.pochitushki.service.telegram.card.CardList
import ru.proshik.pochitushki.service.telegram.card.CardRef
import ru.proshik.pochitushki.service.telegram.card.CardView
import ru.proshik.pochitushki.service.telegram.card.PostLoc
import ru.proshik.pochitushki.service.telegram.card.cardCallback

@Service
class TelegramKeyboard(private val i18nService: I18nService) {

    companion object {

        const val CALLBACK_PROFILE_LANGUAGE_SETTINGS = "profile_language_settings"

        const val CALLBACK_PROFILE_LANGUAGE_SETTINGS_CHANGE = "profile_language_settings_changed"
        const val CALLBACK_PROFILE_BACK_SETTINGS = "profile_back_button_settings"

        const val CALLBACK_PROFILE_IMPORT = "profile_import"
        const val CALLBACK_PROFILE_EXPORT = "profile_export"

        // Card symbols: the same in every language, so they are not resource-bundle text.
        private const val STAR_ON = "★"
        private const val STAR_OFF = "☆"
        private const val TRASH = "🗑"
        private const val MORE = "⋯"
        private const val PREV = "‹"
        private const val NEXT = "›"
        private const val INERT = "·"
        private val PAGED_LISTS = setOf(CardList.UNREAD, CardList.ARCHIVE, CardList.FAVORITES)

    }

    /**
     * The post card's buttons. Every button carries the card's whole ref, see CardRef.
     * ```
     * [ ✓ В архив ] [ ☆ ] [ 🗑 ] [ ⋯ ]
     * [    ‹    ] [ 3 / 42 ] [    ›    ]
     * ```
     * The arrows turn into inert dots at the ends so the row keeps its shape.
     */
    fun buildCardKeyboard(view: CardView.Post, languageCode: String): InlineKeyboard {
        val ref = view.ref
        fun button(text: String, action: String, extra: String? = null) =
            InlineButton(text, callbackData = cardCallback(action, ref, extra))

        if (view.confirmDelete) {
            return InlineKeyboard(
                listOf(
                    listOf(
                        button(i18nService.getMessage("card.delete.confirm", languageCode), CardAction.DELETE_YES),
                        button(i18nService.getMessage("card.delete.cancel", languageCode), CardAction.DELETE_NO),
                    )
                )
            )
        }

        val move = if (ref.loc == PostLoc.ARCHIVE) {
            button(i18nService.getMessage("card.button.unread", languageCode), CardAction.UNREAD)
        } else {
            button(i18nService.getMessage("card.button.archive", languageCode), CardAction.ARCHIVE)
        }
        val actions = listOf(
            move,
            button(if (view.post.isFavorite) STAR_ON else STAR_OFF, CardAction.FAVORITE),
            button(TRASH, CardAction.DELETE_ASK),
            button(MORE, CardAction.MORE, if (view.expanded) "0" else "1"),
        )

        val rows = mutableListOf(actions)
        if (view.expanded) {
            rows += listOf(
                button(i18nService.getMessage("command.feed.button.pdf", languageCode), CardAction.PDF),
                button(i18nService.getMessage("command.feed.button.labels", languageCode), CardAction.LABELS),
            )
        }
        if (ref.list == CardList.RANDOM) {
            rows += listOf(button(i18nService.getMessage("card.button.random_more", languageCode), CardAction.RANDOM_NEXT))
        }
        if (ref.list in PAGED_LISTS) {
            val last = view.total - 1
            rows += listOf(
                if (ref.offset > 0) {
                    InlineButton(PREV, callbackData = cardCallback(CardAction.NAV, ref.at(ref.offset - 1)))
                } else {
                    button(INERT, CardAction.NOOP)
                },
                button("${ref.offset + 1} / ${view.total}", CardAction.NOOP),
                if (ref.offset < last) {
                    InlineButton(NEXT, callbackData = cardCallback(CardAction.NAV, ref.at(ref.offset + 1)))
                } else {
                    button(INERT, CardAction.NOOP)
                },
            )
        }
        return InlineKeyboard(rows)
    }

    /**
     * The label picker that replaces a post card's buttons:
     * [ ✅ kotlin ] [ ⬜ вечером ] … [ ➕ Новая метка ] [ ◀️ Назад ]
     */
    fun buildPostLabelsKeyboard(
        ref: CardRef,
        labels: List<LabelData>,
        attachedIds: Set<Long>,
        languageCode: String,
    ): InlineKeyboard {
        val rows = labels.map { label ->
            val mark = if (label.id in attachedIds) "✅" else "⬜"
            listOf(InlineButton("$mark ${label.name}", callbackData = cardCallback(CardAction.LABEL_TOGGLE, ref, label.id.toString())))
        }
        val footer = listOf(
            InlineButton(
                i18nService.getMessage("command.labels.button.new", languageCode),
                callbackData = cardCallback(CardAction.LABEL_NEW, ref),
            ),
            InlineButton(
                i18nService.getMessage("command.profile.language.back", languageCode),
                callbackData = cardCallback(CardAction.LABELS_BACK, ref),
            ),
        )
        return InlineKeyboard(rows + listOf(footer))
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
                        text = i18nService.getMessage("command.profile.settings.language", languageCode),
                        callbackData = "$CALLBACK_PROFILE_LANGUAGE_SETTINGS|_"
                    ),
                ),
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.profile.import", languageCode),
                        callbackData = "$CALLBACK_PROFILE_IMPORT|_"
                    ),
                ),
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = i18nService.getMessage("command.profile.export", languageCode),
                        callbackData = "$CALLBACK_PROFILE_EXPORT|_"
                    ),
                ),
            )
        )
    }

    /**
     * PDF engine choice in place of the card's buttons, when more than one engine is on:
     * [ OpenHTML ] [ Playwright ] [ ◀️ Back ]
     */
    fun buildPdfEngineKeyboard(ref: CardRef, engines: List<String>, languageCode: String): InlineKeyboard {
        val choices = engines.map { engine ->
            InlineButton(
                i18nService.getMessage("command.pdf.engine.$engine", languageCode),
                callbackData = cardCallback(CardAction.PDF_ENGINE, ref, engine),
            )
        }
        val back = InlineButton(
            i18nService.getMessage("command.profile.language.back", languageCode),
            callbackData = cardCallback(CardAction.MORE, ref, "0"),
        )
        return InlineKeyboard(listOf(choices + back))
    }

}
