package ru.proshik.pochitushki.service.telegram.widget

import java.time.LocalDate
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.service.CoverService
import ru.proshik.pochitushki.service.I18nService
import ru.proshik.pochitushki.service.telegram.card.CardPaging
import ru.proshik.pochitushki.service.telegram.card.CardView
import ru.proshik.pochitushki.service.telegram.card.PostCardRenderer
import tools.jackson.databind.json.JsonMapper

/**
 * The widget's `chat_data`: what [WidgetTemplate] draws, in one-letter keys because Telegram
 * allows 1024 bytes of UTF-8. The UI strings ride along (`s`) so the widget text still comes
 * from I18nService. When the post does not fit, the title is shortened first, then labels go
 * from the end, then the URL; the ref (`r`) is never dropped — without it no button works.
 */
@Component
class WidgetChatData(
    private val coverService: CoverService,
    private val i18n: I18nService,
    private val renderer: PostCardRenderer,
) {

    private val mapper = JsonMapper.builder().build()

    /** The packed chat_data, and whether the article URL made it in (`u`) for openLink. */
    data class Packed(val json: String, val link: Boolean)

    fun encode(view: CardView, languageCode: String, today: LocalDate = LocalDate.now()): String =
        pack(view, languageCode, today).json

    /**
     * When `link` is false the widget cannot open the article itself — the URL was never
     * openable, or did not fit — and the keyboard under it has to carry a link button.
     */
    fun pack(view: CardView, languageCode: String, today: LocalDate = LocalDate.now()): Packed = when (view) {
        is CardView.Notice -> Packed(mapper.writeValueAsString(linkedMapOf("e" to 1, "m" to view.text)), link = false)
        is CardView.Post -> encodePost(view, languageCode, today)
    }

    private fun encodePost(view: CardView.Post, lang: String, today: LocalDate): Packed {
        val post = view.post
        val cover = coverService.decorate(post, showOgCovers = false)
        var title = post.title?.takeIf { it.isNotBlank() } ?: post.url
        // Only what can possibly fit goes into the loop below: a post may carry thousands of
        // imported labels, and dropping them one by one with a full serialization each time
        // would stall the bot's single dispatcher thread.
        var labels = labelsWithin(post.labels.map { it.name }, MAX_BYTES)
        var url: String? = post.url.takeIf(::canOpen)
        val strings = linkedMapOf(
            "a" to i18n.getMessage("card.button.archive", lang),
            "u" to i18n.getMessage("card.button.unread", lang),
            "y" to i18n.getMessage("card.delete.confirm", lang),
            "n" to i18n.getMessage("card.delete.cancel", lang),
            "r" to i18n.getMessage("card.button.random_more", lang),
        )

        while (true) {
            val data = linkedMapOf<String, Any>(
                "r" to view.ref.copy(widget = true).encode(),
                "t" to title,
                "d" to cover.domain,
                "a" to renderer.dateLabel(post, lang, today),
                "l" to labels,
                "f" to post.isFavorite,
                "n" to CardPaging.shownTotal(view),
                "o" to view.ref.offset,
                "p" to cover.palette,
                "c" to cover.comp,
                "b" to cover.abbrev,
                "s" to strings,
            )
            url?.let { data["u"] = it }
            // Where "›" leads; absent at the end. Not always offset + 1: see CardPaging.
            CardPaging.nextOffset(view)?.let { data["j"] = it }
            if (view.confirmDelete) data["x"] = 1
            val json = mapper.writeValueAsString(data)
            if (json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return Packed(json, link = url != null)

            when {
                // Strictly shorter every time (the ellipsis counts), or this would never end.
                title.length > MIN_TITLE + 1 -> title = title.take(maxOf(MIN_TITLE, title.length * 3 / 4)).trimEnd() + "…"
                labels.isNotEmpty() -> labels = labels.dropLast(1)
                url != null -> url = null
                else -> return Packed(json, link = false) // cannot happen with the fixed fields; Telegram would refuse it
            }
        }
    }

    private fun labelsWithin(names: List<String>, budget: Int): List<String> {
        var used = 0
        return names.takeWhile { name ->
            used += name.toByteArray(Charsets.UTF_8).size + LABEL_OVERHEAD
            used <= budget
        }
    }

    companion object {
        const val MAX_BYTES = 1024
        /** TelegramWidget.openLink accepts at most 256 characters. */
        const val MAX_OPEN_LINK = 256
        private const val MIN_TITLE = 20
        /** Quotes and comma around each label in the JSON array. */
        private const val LABEL_OVERHEAD = 3
        private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

        /**
         * Whether TelegramWidget.openLink takes [url]: https only, at most 256 characters, no
         * user name or password, and not a Telegram link. Anything else is opened by an inline
         * URL button under the widget.
         */
        fun canOpen(url: String): Boolean {
            if (url.length > MAX_OPEN_LINK || !url.startsWith("https://")) return false
            val uri = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
            if (uri.rawUserInfo != null) return false
            val host = uri.host?.lowercase() ?: return false
            return TELEGRAM_HOSTS.none { host == it || host.endsWith(".$it") }
        }
    }
}
