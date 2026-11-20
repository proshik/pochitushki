package ru.proshik.pochitushki.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.PostData

/**
 * Cards for the web pages: CoverService's cover plus what depends on the reader's locale and on
 * today — the age of an unread post and the date-group headings of the feed.
 *
 * "Today" is the JVM's default zone, the same one the saved dates are read in
 * (`Timestamp.toLocalDateTime`): the hero's age has always been counted this way.
 */
@Service
class PostViewService(
    private val coverService: CoverService,
    private val messageSource: MessageSource,
) {

    fun card(post: PostData, showOgCovers: Boolean, locale: Locale, today: LocalDate = LocalDate.now()): CoverView =
        withAge(coverService.decorate(post, showOgCovers), locale, today)

    /** Cards without group headings — archive, favorites, random, a label's posts. */
    fun cards(posts: List<PostData>, showOgCovers: Boolean, locale: Locale, today: LocalDate = LocalDate.now()): List<CoverView> =
        posts.map { card(it, showOgCovers, locale, today) }

    /**
     * Cards with a heading before the first card of every date group (`/` and `/all`).
     *
     * @param previous the post just before this page in the same order, or null on the first
     * page. A page that continues the previous group starts without a heading, so infinite
     * scroll never prints the same heading twice at the seam.
     */
    fun groupedCards(
        posts: List<PostData>,
        previous: PostData?,
        showOgCovers: Boolean,
        locale: Locale,
        today: LocalDate = LocalDate.now(),
    ): List<CoverView> {
        var last = previous?.let { DateGroup.of(it.createdDate.toLocalDate(), today) }
        var firstOnPage = previous == null
        return posts.map { post ->
            val cv = card(post, showOgCovers, locale, today)
            val group = DateGroup.of(post.createdDate.toLocalDate(), today)
            if (group == last) {
                cv
            } else {
                last = group
                cv.copy(group = DateGroupView(groupTitle(group, today, locale), first = firstOnPage))
                    .also { firstOnPage = false }
            }
        }
    }

    private fun withAge(cv: CoverView, locale: Locale, today: LocalDate): CoverView {
        if (cv.post.isArchived) return cv
        val saved = cv.post.createdDate.toLocalDate()
        val days = ChronoUnit.DAYS.between(saved, today).coerceAtLeast(0)
        val text = if (days == 0L) {
            messageSource.getMessage("web.age.today", null, locale)
        } else {
            messageSource.plural("web.age.days", days, locale)
        }
        val title = messageSource.getMessage("web.age.saved", arrayOf(saved.format(SAVED_ON)), locale)
        return cv.copy(age = AgeView(days = days, text = text, title = title, old = days >= OLD_AFTER_DAYS))
    }

    private fun groupTitle(group: DateGroup, today: LocalDate, locale: Locale): String = when (group) {
        DateGroup.Today -> messageSource.getMessage("web.group.today", null, locale)
        DateGroup.ThisWeek -> messageSource.getMessage("web.group.week", null, locale)
        DateGroup.EarlierThisMonth ->
            messageSource.getMessage("web.group.earlier.${today.monthValue}", null, locale)
        is DateGroup.Month -> {
            val name = messageSource.getMessage("web.group.month.${group.month}", null, locale)
            if (group.year == today.year) name else "$name ${group.year}"
        }
    }

    companion object {
        /** From this many days an unread link reads in ink: it has waited a month. */
        const val OLD_AFTER_DAYS = 30L

        private val SAVED_ON = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}

/**
 * Where a saved date falls relative to today: today, earlier this week (weeks start on Monday),
 * earlier this month, or a calendar month. A date after today (clock skew) counts as today.
 */
sealed interface DateGroup {
    data object Today : DateGroup
    data object ThisWeek : DateGroup
    data object EarlierThisMonth : DateGroup
    data class Month(val year: Int, val month: Int) : DateGroup

    companion object {
        fun of(date: LocalDate, today: LocalDate): DateGroup {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return when {
                !date.isBefore(today) -> Today
                !date.isBefore(monday) -> ThisWeek
                date.year == today.year && date.month == today.month -> EarlierThisMonth
                else -> Month(date.year, date.monthValue)
            }
        }
    }
}
