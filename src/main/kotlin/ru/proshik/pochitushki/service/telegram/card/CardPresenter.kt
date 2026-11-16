package ru.proshik.pochitushki.service.telegram.card

import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.service.telegram.TelegramKeyboard
import ru.proshik.pochitushki.service.telegram.api.InlineKeyboard
import ru.proshik.pochitushki.service.telegram.api.LinkPreview
import ru.proshik.pochitushki.service.telegram.api.TelegramBotApi

/** What a card shows. [PostCardService] decides it; a [CardPresenter] draws it. */
sealed interface CardView {
    data class Post(
        val post: PostData,
        val ref: CardRef,
        /** Posts in the list, for "n / N". Unused for RANDOM and SAVED. */
        val total: Int,
        val expanded: Boolean = false,
        val confirmDelete: Boolean = false,
        /** A line above the post, e.g. "Saved ✅" on the reply to a link. */
        val header: String? = null,
    ) : CardView

    /** Plain text and no buttons: an empty list, "all read", the outcome of a saved reply. */
    data class Notice(val text: String) : CardView
}

/** The answer to a button press: an optional toast. */
data class CallbackAnswer(val text: String? = null)

/** Draws a [CardView] into a chat message — as text, or (step 4) as a widget. */
interface CardPresenter {
    fun send(chatId: Long, view: CardView, languageCode: String, replyTo: Long? = null): Boolean
    fun redraw(chatId: Long, messageId: Long, view: CardView, languageCode: String): Boolean
    fun redrawKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean
}

/** The card as an HTML text message whose link preview follows the post's URL. */
@Component
class TextCardPresenter(
    private val api: TelegramBotApi,
    private val renderer: PostCardRenderer,
    private val keyboard: TelegramKeyboard,
) : CardPresenter {

    override fun send(chatId: Long, view: CardView, languageCode: String, replyTo: Long?): Boolean {
        val (html, preview, markup) = draw(view, languageCode)
        return api.sendHtml(chatId, html, preview, markup, replyTo) != null
    }

    override fun redraw(chatId: Long, messageId: Long, view: CardView, languageCode: String): Boolean {
        val (html, preview, markup) = draw(view, languageCode)
        // Editing text without reply_markup would leave the old buttons on a notice; an empty
        // keyboard removes them explicitly.
        return api.editHtml(chatId, messageId, html, preview, markup ?: NO_BUTTONS)
    }

    override fun redrawKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean =
        api.editKeyboard(chatId, messageId, keyboard)

    private fun draw(view: CardView, languageCode: String): Triple<String, LinkPreview?, InlineKeyboard?> = when (view) {
        is CardView.Post -> {
            val text = renderer.render(view.post, languageCode)
            val html = view.header?.let { escapeHtml(it) + "\n\n" + text.html } ?: text.html
            Triple(html, LinkPreview(text.previewUrl), keyboard.buildCardKeyboard(view, languageCode))
        }
        is CardView.Notice -> Triple(escapeHtml(view.text), null, null)
    }

    private companion object {
        val NO_BUTTONS = InlineKeyboard(emptyList())
    }
}
