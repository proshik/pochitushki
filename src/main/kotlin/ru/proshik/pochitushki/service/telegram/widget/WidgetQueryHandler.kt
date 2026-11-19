package ru.proshik.pochitushki.service.telegram.widget

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.service.telegram.api.TelegramBotApi
import ru.proshik.pochitushki.service.metrics.AppMetrics
import ru.proshik.pochitushki.service.telegram.card.CallbackAnswer
import ru.proshik.pochitushki.service.telegram.card.CardAction
import ru.proshik.pochitushki.service.telegram.card.PostCardService
import ru.proshik.pochitushki.service.telegram.card.parseCardCallback
import tools.jackson.databind.JsonNode

/**
 * `widget_query` — a tap inside a widget card. The library does not know this update, so
 * TelegramController hands it here before the library sees the body. The data is the same
 * `<action>|<ref>` as a card's callback_data and goes through the same PostCardService.
 *
 * Every query gets exactly one answerWidgetQuery, failures included, or the widget shows
 * TIMEOUT after 15 seconds; the answer's data is the toast, if any.
 */
@Component
class WidgetQueryHandler(
    private val postCardService: PostCardService,
    private val api: TelegramBotApi,
    private val metrics: AppMetrics,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(query: JsonNode) {
        val id = query.get("id")?.asString() ?: return
        var answer = CallbackAnswer()
        try {
            answer = act(query)
        } catch (e: Exception) {
            metrics.operationFailed(AppMetrics.Operation.CARD_ACTION)
            logger.warn("widget query handling failed: {}", e.javaClass.simpleName, e)
        } finally {
            api.answerWidgetQuery(id, answer.text)
        }
    }

    /** A query from a forwarded shared widget. This bot never shares widgets: answer and move on. */
    fun handleShared(query: JsonNode) {
        query.get("id")?.asString()?.let { api.answerWidgetQuery(it, null) }
    }

    private fun act(query: JsonNode): CallbackAnswer {
        // Inline-mode widgets come without chat/message_id; the bot does not send those.
        val chatId = query.path("chat").path("id").takeIf { it.isNumber }?.asLong() ?: return CallbackAnswer()
        val messageId = query.path("message_id").takeIf { it.isNumber }?.asLong() ?: return CallbackAnswer()
        val data = query.get("data")?.asString() ?: return CallbackAnswer()

        val parts = data.split("|", limit = 2)
        val action = parts[0]
        if (action !in CardAction.ALL || parts.size < 2) return CallbackAnswer()
        val (ref, extra) = parseCardCallback(parts[1]) ?: return CallbackAnswer()
        return postCardService.onCallback(chatId, messageId, action, ref, extra)
    }
}
