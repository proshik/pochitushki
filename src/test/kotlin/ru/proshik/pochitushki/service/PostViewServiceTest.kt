package ru.proshik.pochitushki.service

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.configuration.MessageConfiguration
import ru.proshik.pochitushki.model.PostData

class PostViewServiceTest {

    private val service = PostViewService(CoverService(), MessageConfiguration().messageSource())

    private val ru = Locale("ru")
    private val en = Locale("en")

    /** A Friday: the week began on Monday 16.11. */
    private val today = LocalDate.of(2026, 11, 20)

    private var nextId = 1L

    private fun post(saved: LocalDate, archived: Boolean = false) = PostData(
        id = nextId++,
        title = "Title",
        url = "https://example.com/${nextId}",
        userId = 1,
        tags = null,
        isArchived = archived,
        createdDate = saved.atTime(12, 0),
        updatedDate = LocalDateTime.now(),
    )

    private fun ageOf(daysAgo: Long, locale: Locale = ru) =
        service.card(post(today.minusDays(daysAgo)), showOgCovers = true, locale, today).age!!

    @Test
    fun `age of an unread post uses russian plural forms`() {
        assertEquals("сегодня", ageOf(0).text)
        assertEquals("лежит 1 день", ageOf(1).text)
        assertEquals("лежит 2 дня", ageOf(2).text)
        assertEquals("лежит 4 дня", ageOf(4).text)
        assertEquals("лежит 5 дней", ageOf(5).text)
        assertEquals("лежит 11 дней", ageOf(11).text)
        assertEquals("лежит 14 дней", ageOf(14).text)
        assertEquals("лежит 21 день", ageOf(21).text)
        assertEquals("лежит 42 дня", ageOf(42).text)
    }

    @Test
    fun `age in english`() {
        assertEquals("today", ageOf(0, en).text)
        assertEquals("waiting 1 day", ageOf(1, en).text)
        assertEquals("waiting 2 days", ageOf(2, en).text)
    }

    @Test
    fun `a month on the shelf reads as old, and the title keeps the saved date`() {
        assertFalse(ageOf(29).old)
        assertTrue(ageOf(30).old)
        assertEquals("Сохранена 21.10.2026", ageOf(30).title)
    }

    @Test
    fun `archived posts have no age`() {
        assertNull(service.card(post(today.minusDays(3), archived = true), true, ru, today).age)
    }

    @Test
    fun `groups go today, this week, earlier this month, month, month with year`() {
        val posts = listOf(
            post(today),
            post(today.minusDays(1)),           // Thu 19.11
            post(LocalDate.of(2026, 11, 16)),    // Monday — still this week
            post(LocalDate.of(2026, 11, 15)),    // Sunday before
            post(LocalDate.of(2026, 11, 2)),
            post(LocalDate.of(2026, 10, 30)),
            post(LocalDate.of(2025, 12, 31)),
        )
        val headings = service.groupedCards(posts, previous = null, showOgCovers = true, ru, today)
            .map { it.group?.title }

        assertEquals(
            listOf("Сегодня", "На этой неделе", null, "Раньше в ноябре", null, "Октябрь", "Декабрь 2025"),
            headings,
        )
    }

    @Test
    fun `only the first heading on the page is marked first`() {
        val cards = service.groupedCards(
            listOf(post(today), post(LocalDate.of(2026, 10, 1))), previous = null, true, ru, today,
        )
        assertTrue(cards[0].group!!.first)
        assertFalse(cards[1].group!!.first)
    }

    @Test
    fun `a page continuing the previous group starts without its heading`() {
        val previous = post(LocalDate.of(2026, 10, 5))
        val cards = service.groupedCards(
            listOf(post(LocalDate.of(2026, 10, 3)), post(LocalDate.of(2026, 9, 30))), previous, true, ru, today,
        )
        assertNull(cards[0].group)
        assertEquals("Сентябрь", cards[1].group!!.title)
        assertFalse(cards[1].group!!.first)
    }

    @Test
    fun `oldest first order gives headings in reverse`() {
        val cards = service.groupedCards(
            listOf(post(LocalDate.of(2026, 10, 1)), post(today.minusDays(1)), post(today)), null, true, en, today,
        )
        assertEquals(listOf("October", "This week", "Today"), cards.map { it.group?.title })
    }

    @Test
    fun `on a monday nothing but today is this week`() {
        val monday = LocalDate.of(2026, 11, 16)
        assertEquals(DateGroup.Today, DateGroup.of(monday, monday))
        assertEquals(DateGroup.EarlierThisMonth, DateGroup.of(monday.minusDays(1), monday))
    }

    @Test
    fun `a week reaching into last month stays this week`() {
        val thursday = LocalDate.of(2026, 10, 1) // Monday was 28.09
        assertEquals(DateGroup.ThisWeek, DateGroup.of(LocalDate.of(2026, 9, 28), thursday))
        assertEquals(DateGroup.Month(2026, 9), DateGroup.of(LocalDate.of(2026, 9, 27), thursday))
    }
}
