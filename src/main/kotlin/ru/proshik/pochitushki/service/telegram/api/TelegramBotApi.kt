package ru.proshik.pochitushki.service.telegram.api

import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * [TelegramBotApiClient] with the failure handling the card needs: nothing is thrown, and
 * redrawing a card into exactly what it already shows is not an error.
 *
 * Logging is deliberately narrow. A FeignException's message carries the request URL, and
 * the URL carries the bot token (`…/bot<token>/sendMessage`), so only the HTTP status and
 * Telegram's own `description` go to the log, never the exception.
 */
@Service
class TelegramBotApi(private val client: TelegramBotApiClient) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** @return the sent message's id, or null when Telegram refused or could not be reached. */
    fun sendHtml(chatId: Long, html: String, preview: LinkPreview?, keyboard: InlineKeyboard?, replyTo: Long? = null): Long? =
        call("sendMessage", onNotModified = null) {
            val response = client.sendMessage(
                SendMessageRequest(
                    chatId = chatId,
                    text = html,
                    linkPreviewOptions = preview,
                    replyMarkup = keyboard,
                    replyParameters = replyTo?.let { ReplyTo(it) },
                )
            )
            messageId(response)
        }

    fun editHtml(chatId: Long, messageId: Long, html: String, preview: LinkPreview?, keyboard: InlineKeyboard?): Boolean =
        call("editMessageText", onNotModified = true) {
            client.editMessageText(
                EditMessageTextRequest(chatId, messageId, html, linkPreviewOptions = preview, replyMarkup = keyboard)
            ).ok
        } ?: false

    /** A null keyboard removes the buttons. */
    fun editKeyboard(chatId: Long, messageId: Long, keyboard: InlineKeyboard?): Boolean =
        call("editMessageReplyMarkup", onNotModified = true) {
            client.editMessageReplyMarkup(EditReplyMarkupRequest(chatId, messageId, keyboard ?: EMPTY)).ok
        } ?: false

    fun setWebhook(url: String, allowedUpdates: List<String>, secretToken: String?): Boolean =
        call("setWebhook", onNotModified = null) {
            client.setWebhook(SetWebhookRequest(url, allowedUpdates, secretToken)).ok
        } ?: false

    /** @return the widget message's id, or null on any refusal — the caller falls back to text. */
    fun sendWidget(chatId: Long, html: String, chatData: String, keyboard: InlineKeyboard?, replyTo: Long? = null): Long? =
        call("sendWidget", onNotModified = null) {
            messageId(
                client.sendWidget(
                    SendWidgetRequest(chatId, html, chatData, replyMarkup = keyboard, replyParameters = replyTo?.let { ReplyTo(it) })
                )
            )
        }

    fun editWidgetData(chatId: Long, messageId: Long, chatData: String): Boolean =
        call("editMessageWidgetData", onNotModified = true) {
            client.editMessageWidgetData(EditWidgetDataRequest(chatId, messageId, chatData)).ok
        } ?: false

    fun answerWidgetQuery(queryId: String, data: String?): Boolean =
        call("answerWidgetQuery", onNotModified = true) {
            client.answerWidgetQuery(AnswerWidgetQueryRequest(queryId, data)).ok
        } ?: false

    private fun messageId(response: TgResponse<JsonNode>): Long? =
        response.result?.get("message_id")?.takeIf { it.isNumber }?.asLong()

    /**
     * Runs one Bot API call. `onNotModified` is what a "message is not modified" refusal
     * means for this call: a redraw that changes nothing has still done its job.
     */
    private fun <T> call(method: String, onNotModified: T?, block: () -> T?): T? = try {
        block()
    } catch (e: FeignException) {
        val description = description(e)
        if (e.status() == 400 && description?.contains(NOT_MODIFIED) == true) {
            logger.debug("telegram {}: not modified", method)
            onNotModified
        } else {
            logger.warn("telegram {} failed: status={}, description={}", method, e.status(), description)
            null
        }
    } catch (e: Exception) {
        // Connection errors carry the URL (and so the token) in their message as well.
        logger.warn("telegram {} failed: {}", method, e.javaClass.simpleName)
        null
    }

    private fun description(e: FeignException): String? = try {
        mapper.readTree(e.contentUTF8())?.get("description")?.asString()
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val NOT_MODIFIED = "message is not modified"
        private val EMPTY = InlineKeyboard(emptyList())
    }
}
