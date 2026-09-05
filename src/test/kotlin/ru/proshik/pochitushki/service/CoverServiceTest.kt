package ru.proshik.pochitushki.service

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.model.PostData

class CoverServiceTest {

    private val coverService = CoverService()

    private fun post(
        url: String = "https://habr.com/ru/articles/1/",
        title: String? = "Заголовок статьи",
        ogImageUrl: String? = null,
    ) = PostData(
        id = 1,
        title = title,
        url = url,
        userId = 1,
        tags = null,
        ogImageUrl = ogImageUrl,
        createdDate = LocalDateTime.now(),
        updatedDate = LocalDateTime.now(),
    )

    @Test
    fun `same domain always gets the same palette, www ignored`() {
        val a = coverService.decorate(post(url = "https://habr.com/ru/articles/1/"))
        val b = coverService.decorate(post(url = "https://www.habr.com/ru/articles/2/", title = "Другая статья"))
        assertEquals(a.palette, b.palette)
        assertEquals("habr.com", a.domain)
        assertEquals("habr.com", b.domain)
    }

    @Test
    fun `palette is one of the 12 fixed classes`() {
        val palettes = (0..11).map { "cv-$it" }.toSet()
        listOf("https://habr.com/x", "https://grugbrain.dev/", "https://mcfunley.com/a", "https://kotlinlang.org/docs")
            .forEach { url ->
                val cover = coverService.decorate(post(url = url))
                assertTrue(cover.palette in palettes, "unexpected palette ${cover.palette}")
            }
    }

    @Test
    fun `og image forces the og composition`() {
        val cover = coverService.decorate(post(ogImageUrl = "https://example.com/img.png"))
        assertEquals("co-og", cover.comp)
    }

    @Test
    fun `composition is deterministic by title and is one of three`() {
        val comps = setOf("co-mono", "co-band", "co-frame")
        val a = coverService.decorate(post(title = "Choose Boring Technology"))
        val b = coverService.decorate(post(title = "Choose Boring Technology"))
        assertEquals(a.comp, b.comp)
        assertTrue(a.comp in comps, "unexpected comp ${a.comp}")
    }

    @Test
    fun `short first significant word becomes the abbreviation as-is`() {
        val cover = coverService.decorate(post(title = "Груг против сложности"))
        assertEquals("Груг", cover.abbrev)
    }

    @Test
    fun `two long words give two initials`() {
        val cover = coverService.decorate(post(title = "Coroutines Guide: Structured Concurrency"))
        assertEquals("CG", cover.abbrev)
    }

    @Test
    fun `single long word is trimmed to four letters`() {
        val cover = coverService.decorate(post(title = "Внутренности"))
        assertEquals("Внут", cover.abbrev)
    }

    @Test
    fun `stopwords are skipped when picking the abbreviation`() {
        // "Как" и "что" — стоп-слова; первое значимое слово — "устроен"
        val cover = coverService.decorate(post(title = "Как устроен движок и что дальше"))
        assertEquals("УД", cover.abbrev) // устроен + движок
    }

    @Test
    fun `blank title falls back to domain letters`() {
        val cover = coverService.decorate(post(title = null))
        assertEquals("HA", cover.abbrev)
    }

    @Test
    fun `url without a host does not crash and uses fallback domain`() {
        val cover = coverService.decorate(post(url = "notaurl", title = null))
        assertEquals("ссылка", cover.domain)
        assertTrue(cover.palette.startsWith("cv-"))
    }

    @Test
    fun `decorate list preserves order`() {
        val posts = listOf(post(url = "https://a.com/1"), post(url = "https://b.com/2"))
        val covers = coverService.decorate(posts)
        assertEquals(listOf("a.com", "b.com"), covers.map { it.domain })
    }

    @Test
    fun `long host is shortened to two labels on the cover plate only`() {
        val cover = coverService.decorate(post(url = "https://journal.stuffwithstuff.com/2015/02/01/"))
        assertEquals("journal.stuffwithstuff.com", cover.domain)
        assertEquals("stuffwithstuff.com", cover.coverDomain)

        val short = coverService.decorate(post(url = "https://habr.com/x"))
        assertEquals("habr.com", short.coverDomain)
    }

    @Test
    fun `og photo is used as the composition when photo covers are on`() {
        val cv = coverService.decorate(post(ogImageUrl = "https://cdn.example.com/pic.png"), showOgCovers = true)

        assertEquals("co-og", cv.comp)
    }

    @Test
    fun `og photo is ignored when the reader turned photo covers off`() {
        val cv = coverService.decorate(post(ogImageUrl = "https://cdn.example.com/pic.png"), showOgCovers = false)

        assertTrue(cv.comp in setOf("co-mono", "co-band", "co-frame"), "got ${cv.comp}")
    }

    @Test
    fun `turning photo covers off keeps the domain palette intact`() {
        val withPhoto = coverService.decorate(post(ogImageUrl = "https://cdn.example.com/pic.png"), showOgCovers = true)
        val without = coverService.decorate(post(ogImageUrl = "https://cdn.example.com/pic.png"), showOgCovers = false)

        assertEquals(withPhoto.palette, without.palette)
    }

    @Test
    fun `the list overload passes the flag to every cover`() {
        val posts = listOf(post(ogImageUrl = "https://cdn.example.com/a.png"), post(ogImageUrl = "https://cdn.example.com/b.png"))

        assertTrue(coverService.decorate(posts, showOgCovers = false).none { it.comp == "co-og" })
    }
}
