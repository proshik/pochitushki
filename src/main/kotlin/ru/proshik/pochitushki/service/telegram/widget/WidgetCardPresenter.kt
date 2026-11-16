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
        val data = chatData.encode(view, languageCode)
        return api.sendWidget(chatId, template.html, data, keyboard.buildWidgetKeyboard(view, languageCode), replyTo) != null
    }

    /**
     * Data first, buttons only after it: if Telegram refused the new chat_data the widget still
     * shows the old post, and buttons pointing at the new one would act on a post nobody sees.
     */
    override fun redraw(chatId: Long, messageId: Long, view: CardView, languageCode: String): Boolean {
        if (!api.editWidgetData(chatId, messageId, chatData.encode(view, languageCode))) return false
        val buttons = if (view is CardView.Post) keyboard.buildWidgetKeyboard(view, languageCode) else null
        return api.editKeyboard(chatId, messageId, buttons)
    }

    override fun redrawButtons(chatId: Long, messageId: Long, view: CardView.Post, languageCode: String): Boolean =
        redraw(chatId, messageId, view, languageCode)

    override fun redrawKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean =
        api.editKeyboard(chatId, messageId, keyboard)
}
