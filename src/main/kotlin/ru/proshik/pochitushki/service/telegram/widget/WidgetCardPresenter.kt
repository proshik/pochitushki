package ru.proshik.pochitushki.service.telegram.widget

import org.springframework.stereotype.Component
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard
import ru.proshik.pochitushki.service.telegram.api.InlineKeyboard
import ru.proshik.pochitushki.service.telegram.api.TelegramBotApi
import ru.proshik.pochitushki.service.telegram.card.CardPresenter
import ru.proshik.pochitushki.service.telegram.card.CardView

/**
 * The card as an HTML widget (Bot API 10.4). The widget's own buttons redraw it through
 * chat_data (`editMessageWidgetData`); the keyboard under it (PDF, labels, app) is an ordinary
 * inline keyboard and is edited like one. A notice has no widget form: `send` answers false and
 * PostCardService sends text instead — the same path as any sendWidget refusal.
 */
@Component
class WidgetCardPresenter(
    private val api: TelegramBotApi,
    private val template: WidgetTemplate,
    private val chatData: WidgetChatData,
    private val keyboard: TelegramKeyboard,
) : CardPresenter {

    override fun send(chatId: Long, view: CardView, languageCode: String, replyTo: Long?): Boolean {
        if (view !is CardView.Post) return false
        val packed = chatData.pack(view, languageCode)
        val buttons = keyboard.buildWidgetKeyboard(view, languageCode, linkButton = !packed.link)
        return api.sendWidget(chatId, template.html, packed.json, buttons, replyTo) != null
    }

    /**
     * The widget's data and the keyboard under it are two calls with no common rollback, and
     * the keyboard's buttons name a post. So: take the old buttons off, then change the data,
     * then put the new buttons on. Whichever step Telegram refuses, no button is left acting
     * on a post other than the one the widget shows — at worst there are no buttons until the
     * next tap, and the reader gets the error toast.
     */
    override fun redraw(chatId: Long, messageId: Long, view: CardView, languageCode: String): Boolean {
        if (!api.editKeyboard(chatId, messageId, null)) return false
        val packed = chatData.pack(view, languageCode)
        if (!api.editWidgetData(chatId, messageId, packed.json)) return false
        if (view !is CardView.Post) return true
        return api.editKeyboard(chatId, messageId, keyboard.buildWidgetKeyboard(view, languageCode, linkButton = !packed.link))
    }

    override fun redrawButtons(chatId: Long, messageId: Long, view: CardView.Post, languageCode: String): Boolean =
        redraw(chatId, messageId, view, languageCode)

    override fun redrawKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean =
        api.editKeyboard(chatId, messageId, keyboard)
}
