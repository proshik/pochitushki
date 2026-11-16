package ru.proshik.pochitushki.service.telegram.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Inline keyboard for the calls that go through [TelegramBotApiClient]. The library's own
 * `InlineKeyboardMarkup` is Gson-annotated (`@SerializedName`), and Jackson would put
 * `callbackData` on the wire instead of `callback_data`; hence a small model of our own.
 */
data class InlineKeyboard(@JsonProperty("inline_keyboard") val rows: List<List<InlineButton>>) {
    /** Every button, for the 64-byte callback_data checks. */
    fun buttons(): List<InlineButton> = rows.flatten()
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InlineButton(
    val text: String,
    @JsonProperty("callback_data") val callbackData: String? = null,
    val url: String? = null,
    @JsonProperty("web_app") val webApp: WebAppRef? = null,
)

data class WebAppRef(val url: String)

/** `link_preview_options` for the card: the post's URL, shown large. */
data class LinkPreview(
    val url: String,
    @JsonProperty("prefer_large_media") val preferLargeMedia: Boolean = true,
)
