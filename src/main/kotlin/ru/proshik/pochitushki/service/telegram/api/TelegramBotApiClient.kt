package ru.proshik.pochitushki.service.telegram.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import tools.jackson.databind.JsonNode

/**
 * Bot API calls kotlin-telegram-bot 10.0.0 cannot make: its `editMessageText` has no
 * `link_preview_options`, so the post card is drawn through here. Same `telegram.api-url`
 * as the library, so tests stub both with one WireMock.
 *
 * `telegram.token` has a default on purpose: with `telegram.enabled=false` the token may be
 * absent, and an unresolvable placeholder would stop the application from starting.
 */
@FeignClient(name = "telegram-bot-api", url = "\${telegram.api-url}bot\${telegram.token:}")
interface TelegramBotApiClient {

    @PostMapping("/sendMessage", consumes = ["application/json"])
    fun sendMessage(@RequestBody request: SendMessageRequest): TgResponse<JsonNode>

    @PostMapping("/editMessageText", consumes = ["application/json"])
    fun editMessageText(@RequestBody request: EditMessageTextRequest): TgResponse<JsonNode>

    @PostMapping("/editMessageReplyMarkup", consumes = ["application/json"])
    fun editMessageReplyMarkup(@RequestBody request: EditReplyMarkupRequest): TgResponse<JsonNode>
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
