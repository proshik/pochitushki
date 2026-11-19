package ru.proshik.pochitushki.service.telegram.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import tools.jackson.databind.JsonNode

/**
 * Bot API calls kotlin-telegram-bot 10.0.0 cannot make: its `editMessageText` has no
 * `link_preview_options`, so the post card is drawn through here. Same `telegram.api-url`
 * as the library, so tests stub both with one WireMock.
 *
 * Both placeholders carry defaults on purpose: application.yml has neither key (their defaults
 * live in TelegramProperties, which this annotation never sees), and an unresolvable
 * placeholder stops the application from starting — with `telegram.enabled=false` the token
 * may be absent altogether. Keep the api-url default in step with TelegramProperties.apiUrl.
 */
@FeignClient(name = "telegram-bot-api", url = "\${telegram.api-url:https://api.telegram.org/}bot\${telegram.token:}")
interface TelegramBotApiClient {

    @PostMapping("/sendMessage", consumes = ["application/json"])
    fun sendMessage(@RequestBody request: SendMessageRequest): TgResponse<JsonNode>

    @PostMapping("/editMessageText", consumes = ["application/json"])
    fun editMessageText(@RequestBody request: EditMessageTextRequest): TgResponse<JsonNode>

    @PostMapping("/editMessageReplyMarkup", consumes = ["application/json"])
    fun editMessageReplyMarkup(@RequestBody request: EditReplyMarkupRequest): TgResponse<JsonNode>

    /**
     * The library sends allowed_updates as repeated form fields; the Bot API wants one JSON
     * array, and on a value it cannot parse it silently keeps the bot's previous filter.
     */
    @PostMapping("/setWebhook", consumes = ["application/json"])
    fun setWebhook(@RequestBody request: SetWebhookRequest): TgResponse<JsonNode>

    /** Polled by WebhookHealthCheck for the webhook metrics. */
    @GetMapping("/getWebhookInfo")
    fun getWebhookInfo(): TgResponse<WebhookInfo>

    // HTML widgets, Bot API 10.4 — not in the library at all.

    @PostMapping("/sendWidget", consumes = ["application/json"])
    fun sendWidget(@RequestBody request: SendWidgetRequest): TgResponse<JsonNode>

    @PostMapping("/editMessageWidgetData", consumes = ["application/json"])
    fun editMessageWidgetData(@RequestBody request: EditWidgetDataRequest): TgResponse<JsonNode>

    @PostMapping("/answerWidgetQuery", consumes = ["application/json"])
    fun answerWidgetQuery(@RequestBody request: AnswerWidgetQueryRequest): TgResponse<JsonNode>
}

data class TgResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

data class ReplyTo(@JsonProperty("message_id") val messageId: Long)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SendMessageRequest(
    @JsonProperty("chat_id") val chatId: Long,
    val text: String,
    @JsonProperty("parse_mode") val parseMode: String = "HTML",
    @JsonProperty("link_preview_options") val linkPreviewOptions: LinkPreview? = null,
    @JsonProperty("reply_markup") val replyMarkup: InlineKeyboard? = null,
    @JsonProperty("reply_parameters") val replyParameters: ReplyTo? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EditMessageTextRequest(
    @JsonProperty("chat_id") val chatId: Long,
    @JsonProperty("message_id") val messageId: Long,
    val text: String,
    @JsonProperty("parse_mode") val parseMode: String = "HTML",
    @JsonProperty("link_preview_options") val linkPreviewOptions: LinkPreview? = null,
    @JsonProperty("reply_markup") val replyMarkup: InlineKeyboard? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EditReplyMarkupRequest(
    @JsonProperty("chat_id") val chatId: Long,
    @JsonProperty("message_id") val messageId: Long,
    @JsonProperty("reply_markup") val replyMarkup: InlineKeyboard? = null,
)

/** `allow_queries` must be true, or `TelegramWidget.request` is refused inside the widget. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SendWidgetRequest(
    @JsonProperty("chat_id") val chatId: Long,
    val html: String,
    @JsonProperty("chat_data") val chatData: String,
    @JsonProperty("allow_queries") val allowQueries: Boolean = true,
    @JsonProperty("reply_markup") val replyMarkup: InlineKeyboard? = null,
    @JsonProperty("reply_parameters") val replyParameters: ReplyTo? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EditWidgetDataRequest(
    @JsonProperty("chat_id") val chatId: Long,
    @JsonProperty("message_id") val messageId: Long,
    @JsonProperty("chat_data") val chatData: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AnswerWidgetQueryRequest(
    @JsonProperty("widget_query_id") val widgetQueryId: String,
    val data: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SetWebhookRequest(
    val url: String,
    @JsonProperty("allowed_updates") val allowedUpdates: List<String>,
    @JsonProperty("secret_token") val secretToken: String?,
)

/**
 * The fields of `getWebhookInfo` the health check reads. `url` carries the bot's webhook address:
 * compare it, never log or label it.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class WebhookInfo(
    val url: String = "",
    @JsonProperty("pending_update_count") val pendingUpdateCount: Long = 0,
    /** Unix time of the latest failed delivery; absent when there has been none. */
    @JsonProperty("last_error_date") val lastErrorDate: Long? = null,
    @JsonProperty("last_error_message") val lastErrorMessage: String? = null,
)
