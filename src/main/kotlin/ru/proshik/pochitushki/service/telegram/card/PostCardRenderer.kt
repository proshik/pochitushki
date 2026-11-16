package ru.proshik.pochitushki.service.telegram.card

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.service.CoverService

/** The card's message text (HTML parse mode) and the URL whose preview it shows. */
data class CardText(val html: String, val previewUrl: String)

/**
 * The post card's text:
 * ```
 * <a href="url">Title</a>
 * domain · 20 сент. · #label #label
 * ```
 * HTML rather than MarkdownV2: four characters to escape instead of eighteen.
 */
@Component
class PostCardRenderer(private val coverService: CoverService) {

    fun render(post: PostData, languageCode: String, today: LocalDate = LocalDate.now()): CardText {
        val title = (post.title?.takeIf { it.isNotBlank() } ?: post.url).let(::cut)
        val link = "<a href=\"${escapeHtml(post.url)}\">${escapeHtml(title)}</a>"

        val meta = listOf(
            coverService.decorate(post, showOgCovers = false).domain,
            dateLabel(post, languageCode, today),
        ).joinToString(SEPARATOR) { escapeHtml(it) }

        val head = "$link\n$meta"
        // Labels go in while they fit: a scraped title and a pile of labels must not push the
        // message over Telegram's 4096-character limit and get the whole card refused.
        val labels = StringBuilder()
        for (label in post.labels) {
            val tag = (if (labels.isEmpty()) SEPARATOR else " ") + "#" + escapeHtml(label.name)
            if (head.length + labels.length + tag.length > MAX_TEXT) break
            labels.append(tag)
        }

        return CardText(html = head + labels, previewUrl = post.url)
    }

    /** "20 сент." this year, "1 мар. 2024" otherwise. Shared with the widget. */
    fun dateLabel(post: PostData, languageCode: String, today: LocalDate = LocalDate.now()): String {
        val created = post.createdDate.toLocalDate()
        val pattern = if (created.year == today.year) "d MMM" else "d MMM yyyy"
        return created.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(languageCode)))
    }

    private fun cut(title: String): String =
        if (title.length <= MAX_TITLE) title else title.take(MAX_TITLE - 1).trimEnd() + "…"

    companion object {
        const val MAX_TITLE = 300
        /** Telegram's message text limit. */
        const val MAX_TEXT = 4096
        private const val SEPARATOR = " · "
    }
}

/** Escapes text for Telegram's HTML parse mode, `&` first so the other entities stay intact. */
fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
