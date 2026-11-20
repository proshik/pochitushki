package ru.proshik.pochitushki.service

import java.net.URI
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.PostData

/**
 * Готовая к рендеру обложка поста: CSS-классы палитры/композиции и текстовые
 * детали. Всё детерминировано: один домен всегда даёт один и тот же цвет,
 * один заголовок — одну и ту же композицию. Шаблоны не содержат логики —
 * контроллеры отдают им списки CoverView.
 */
data class CoverView(
    val post: PostData,
    /** CSS-класс палитры домена: "cv-0".."cv-11" (пары цветов заданы в app.css). */
    val palette: String,
    /** CSS-класс композиции: "co-og" | "co-mono" | "co-band" | "co-frame". */
    val comp: String,
    /** Текст для композиции "co-mono" (крупная аббревиатура). */
    val abbrev: String,
    /** Хост без "www." — для мета-строк; палитра хэшируется от него же. */
    val domain: String,
    /** Домен для плашки обложки: длинные хосты укорочены до последних двух меток. */
    val coverDomain: String,
    /**
     * Заголовок для показа: сам заголовок, а без него — URL без схемы и завершающего `/`
     * ([displayUrl]). Только отображение, в данных остаётся как было.
     */
    val title: String = post.title?.takeIf { it.isNotBlank() } ?: displayUrl(post.url),
    /**
     * [title] по кускам, между которыми шаблон ставит `<wbr>`: у URL — после каждого `/`,
     * иначе длинный адрес переносится посреди слова. У настоящего заголовка кусок один.
     */
    val titleParts: List<String> = if (post.title.isNullOrBlank()) splitAfterSlashes(title) else listOf(title),
    /** «лежит N дней» — только у непрочитанных; заполняет PostViewService. */
    val age: AgeView? = null,
    /** Заголовок группы по дате перед этой карточкой (`/` и `/all`); заполняет PostViewService. */
    val group: DateGroupView? = null,
)

data class AgeView(val days: Long, val text: String, val title: String, val old: Boolean)

/** [key]: `today` | `week` | `month` | `YYYY-MM` — app.js puts a new link under `today`. */
data class DateGroupView(val key: String, val title: String, val first: Boolean)

/** `https://site.com/a/b/` → `site.com/a/b`: what a post without a title shows. */
fun displayUrl(url: String): String =
    url.trim().replaceFirst(SCHEME, "").trimEnd('/').ifEmpty { url }

private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

private fun splitAfterSlashes(text: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    text.forEachIndexed { i, c ->
        if (c == '/' && i + 1 < text.length && text[i + 1] != '/') {
            parts += text.substring(start, i + 1)
            start = i + 1
        }
    }
    parts += text.substring(start)
    return parts
}

@Service
class CoverService {

    /**
     * @param showOgCovers when false, a post with an og:image still gets a
     * typographic composition — the reader has asked for no scraped photos.
     */
    fun decorate(post: PostData, showOgCovers: Boolean = true): CoverView {
        val domain = extractDomain(post.url)
        val palette = "cv-" + Math.floorMod(domain.hashCode(), PALETTE_COUNT)
        val comp = if (showOgCovers && !post.ogImageUrl.isNullOrBlank()) {
            "co-og"
        } else {
            "co-" + COMPOSITIONS[Math.floorMod((post.title ?: domain).hashCode(), COMPOSITIONS.size)]
        }
        return CoverView(
            post = post,
            palette = palette,
            comp = comp,
            abbrev = abbrev(post.title, domain),
            domain = domain,
            coverDomain = shortenForCover(domain),
        )
    }

    fun decorate(posts: List<PostData>, showOgCovers: Boolean = true): List<CoverView> =
        posts.map { decorate(it, showOgCovers) }

    private fun extractDomain(url: String): String = try {
        URI(url).host?.removePrefix("www.") ?: DOMAIN_FALLBACK
    } catch (_: Exception) {
        DOMAIN_FALLBACK
    }

    // On the cover plate a long host wraps ugly and collides with the star badge;
    // drop the leftmost labels ("journal.stuffwithstuff.com" → "stuffwithstuff.com").
    private fun shortenForCover(domain: String): String {
        if (domain.length <= COVER_DOMAIN_MAX) return domain
        val labels = domain.split('.')
        return if (labels.size > 2) labels.takeLast(2).joinToString(".") else domain
    }

    private fun abbrev(title: String?, domain: String): String {
        val words = (title ?: "")
            .split(NON_WORD)
            .filter { it.isNotBlank() && it.lowercase() !in STOPWORDS }
        if (words.isEmpty()) return domain.take(2).uppercase()

        val first = words.first()
        if (first.length <= 4) return first

        val initials = words.take(2).joinToString("") { it.first().uppercase() }
        return if (initials.length >= 2) initials else first.take(4)
    }

    companion object {
        private const val PALETTE_COUNT = 12
        private const val COVER_DOMAIN_MAX = 20
        private const val DOMAIN_FALLBACK = "ссылка"
        private val COMPOSITIONS = listOf("mono", "band", "frame")
        private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
        private val STOPWORDS = setOf(
            "и", "в", "на", "не", "по", "для", "как", "что", "из", "о", "а", "с", "у", "к", "за", "это", "или",
            "the", "a", "an", "of", "to", "in", "is", "are", "your", "for", "and", "why", "how", "you", "it",
            "be", "or", "vs",
        )
    }
}
