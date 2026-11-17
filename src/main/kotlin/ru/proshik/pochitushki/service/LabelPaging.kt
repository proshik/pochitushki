package ru.proshik.pochitushki.service

import ru.proshik.pochitushki.model.LabelWithCount

/** One page of /labels and what the pager needs. `total` counts every label, not the page. */
data class LabelsPage(
    val labels: List<LabelWithCount>,
    val page: Int,
    val pages: Int,
    val total: Int,
    /** Page numbers for the pager; null is a gap ("…"). Empty when there is one page. */
    val items: List<Int?>,
)

/**
 * Paging of /labels: 50 a page, the number in `?page=N`. A page past the end shows the last
 * one and anything unreadable shows the first — a stale or hand-edited link still lands
 * somewhere sensible instead of failing.
 */
object LabelPaging {

    const val SIZE = 50

    private val DIGITS = Regex("\\d+")

    fun pages(total: Int): Int = maxOf(1, (total + SIZE - 1) / SIZE)

    fun resolve(raw: String?, total: Int): Int {
        val pages = pages(total)
        val text = raw?.trim().orEmpty()
        if (!text.matches(DIGITS)) return 1
        val n = text.toLongOrNull() ?: return pages
        return n.coerceIn(1L, pages.toLong()).toInt()
    }

    /** The page holding the label at zero-based [position] in name order. */
    fun pageOf(position: Int): Int = position / SIZE + 1

    fun offset(page: Int): Int = (page - 1) * SIZE

    /** First, last and three around the current page; every page when there are few. */
    fun items(page: Int, pages: Int): List<Int?> {
        if (pages <= 1) return emptyList()
        if (pages <= 7) return (1..pages).toList()
        val start = maxOf(1, minOf(page - 1, pages - 2))
        val shown = (setOf(1, pages) + (start..start + 2)).sorted()
        val items = mutableListOf<Int?>()
        var previous: Int? = null
        for (n in shown) {
            if (previous != null && n - previous > 1) items += null
            items += n
            previous = n
        }
        return items
    }
}
