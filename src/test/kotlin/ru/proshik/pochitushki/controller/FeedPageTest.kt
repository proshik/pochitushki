package ru.proshik.pochitushki.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.service.UserService

/** The feed (`/`) and "all" (`/all`): order, label filter, date groups, age, hero. */
@AutoConfigureMockMvc
class FeedPageTest : BaseIntegrationTest() {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var userService: UserService

    private var userId: Long = 0L
    private var nextId = 700_000L

    @BeforeEach
    fun setUp() {
        // Written before feedSort existed: the key is absent.
        userId = user(7101, """{"languageCode":"en","tgFeedEntriesNumber":3,"showOgCovers":true}""")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post_label")
        jdbcTemplate.execute("DELETE FROM post_label")
        jdbcTemplate.execute("DELETE FROM label")
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    private fun user(telegramId: Long, settings: String): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users(telegram_id, username, first_name, settings) VALUES (?, 'u', 'U', ?::jsonb) RETURNING id",
        Long::class.java, telegramId, settings,
    )!!

    /** Explicit ids, so a test can make an unread and an archived post share one. */
    private fun post(
        url: String,
        daysAgo: Int = 0,
        archived: Boolean = false,
        title: String? = url,
        id: Long = nextId++,
        owner: Long = userId,
    ): Long {
        jdbcTemplate.update(
            "INSERT INTO ${if (archived) "archive_post" else "post"}(id, title, url, user_id, created_date) " +
                "VALUES (?, ?, ?, ?, now() - make_interval(days => ?))",
            id, title, url, owner, daysAgo,
        )
        return id
    }

    private fun label(name: String, owner: Long = userId): Long = jdbcTemplate.queryForObject(
        "INSERT INTO label(user_id, name) VALUES (?, ?) RETURNING id", Long::class.java, owner, name,
    )!!

    private fun tag(postId: Long, labelId: Long, archived: Boolean = false) {
        jdbcTemplate.update(
            "INSERT INTO ${if (archived) "archive_post_label" else "post_label"}(post_id, label_id) VALUES (?, ?)",
            postId, labelId,
        )
    }

    private fun page(path: String, vararg params: Pair<String, String>): String {
        val request = get(path).with(withAuth(userId))
        params.forEach { (k, v) -> request.param(k, v) }
        return mockMvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
    }

    private fun setSort(value: String) {
        jdbcTemplate.update(
            "UPDATE users SET settings = settings || jsonb_build_object('feedSort', ?::text) WHERE id = ?", value, userId,
        )
    }

    private fun pressed(html: String, sort: String) =
        Regex("data-feed-sort=\"$sort\"[^>]*aria-pressed=\"true\"").containsMatchIn(html)

    /** Positions inside the list, past the hero (which shows the oldest link first). */
    private fun positions(html: String, vararg needles: String): List<Int> {
        val list = html.substring(html.indexOf("id=\"post-list\""))
        return needles.map { list.indexOf(it) }
    }

    // ── Order ──

    @Test
    fun `settings written before feedSort read as newest first`() {
        assertEquals("newest", userService.getUserByUserId(userId).settings.feedSort)
        post("https://e.com/old", daysAgo = 5)
        post("https://e.com/new", daysAgo = 1)

        val html = page("/")

        val (newer, older) = positions(html, "href=\"https://e.com/new\"", "href=\"https://e.com/old\"")
        assertTrue(newer in 0 until older)
        assertTrue(pressed(html, "newest"))
    }

    @Test
    fun `oldest first applies to the feed, all, and the next page with the right offset`() {
        setSort("oldest")
        (1..25).forEach { post("https://e.com/p$it/", daysAgo = 100 - it) } // p1 is the oldest

        val feed = page("/")
        val all = page("/all")
        val next = page("/api/v1/posts/fragment", "type" to "unread", "offset" to "20")

        val (p1, p2) = positions(feed, "href=\"https://e.com/p1/\"", "href=\"https://e.com/p2/\"")
        assertTrue(p1 in 0 until p2)
        val (allP1, allP2) = positions(all, "href=\"https://e.com/p1/\"", "href=\"https://e.com/p2/\"")
        assertTrue(allP1 in 0 until allP2)
        assertTrue(next.contains("href=\"https://e.com/p21/\"") && next.contains("href=\"https://e.com/p25/\""))
        assertFalse(next.contains("href=\"https://e.com/p20/\""))
        assertTrue(pressed(feed, "oldest"))
    }

    @Test
    fun `the next page keeps the order of the page it continues`() {
        (1..25).forEach { post("https://e.com/p$it/", daysAgo = 100 - it) } // p25 is the newest
        val firstPage = page("/all") // newest first: p25..p6
        assertTrue(firstPage.contains("offset=20&amp;sort=newest"))

        setSort("oldest") // changed in another tab before scrolling
        val next = page("/api/v1/posts/fragment", "type" to "all", "offset" to "20", "sort" to "newest")

        assertTrue(next.contains("https://e.com/p5/") && next.contains("https://e.com/p1/"))
        assertFalse(next.contains("https://e.com/p21/"))
    }

    @Test
    fun `garbage label and sort on the next page are ignored, not a 400`() {
        post("https://e.com/a", archived = true)
        page("/api/v1/posts/fragment", "type" to "archive", "offset" to "0", "label" to "abc", "sort" to "x")
        val html = page("/api/v1/posts/fragment", "type" to "unread", "offset" to "0", "label" to "abc", "sort" to "x")
        assertFalse(html.contains("&amp;label="))
    }

    @Test
    fun `keyboard help offers A only where cards can be moved`() {
        assertTrue(page("/").contains("<kbd>A</kbd>"))
        assertFalse(page("/favorites").contains("<kbd>A</kbd>"))
    }

    @Test
    fun `the hero stays the oldest link whatever the order`() {
        post("https://e.com/oldest", daysAgo = 50)
        post("https://e.com/newest", daysAgo = 1)
        listOf("newest", "oldest").forEach { sort ->
            setSort(sort)
            val html = page("/")
            val hero = html.substring(html.indexOf("id=\"hero\""), html.indexOf("class=\"page-head\""))
            assertTrue(hero.contains("https://e.com/oldest"), sort)
        }
    }

    @Test
    fun `archive keeps newest first even when the feed is oldest first`() {
        setSort("oldest")
        post("https://e.com/a-old", daysAgo = 9, archived = true)
        post("https://e.com/a-new", daysAgo = 1, archived = true)

        val html = page("/archive")

        assertTrue(html.indexOf("https://e.com/a-new") < html.indexOf("https://e.com/a-old"))
        assertFalse(html.contains("data-feed-sort"))
    }

    // ── Label filter ──

    @Test
    fun `no labels, no filter row`() {
        post("https://e.com/x")
        assertFalse(page("/").contains("class=\"label-filter\""))
    }

    @Test
    fun `pills count the section's posts and skip empty labels`() {
        val kotlin = label("kotlin")
        val onlyArchived = label("archived-only")
        label("unused")
        tag(post("https://e.com/u1"), kotlin)
        tag(post("https://e.com/u2"), kotlin)
        tag(post("https://e.com/a1", archived = true), kotlin, archived = true)
        tag(post("https://e.com/a2", archived = true), onlyArchived, archived = true)

        val feed = page("/")
        val all = page("/all")

        fun pill(html: String, name: String) =
            Regex("#$name\\s*<span class=\"pill-n\">(\\d+)</span>").find(html)?.groupValues?.get(1)

        assertTrue(feed.contains("href=\"/?label=$kotlin\""))
        assertEquals("2", pill(feed, "kotlin"))
        assertEquals(null, pill(feed, "archived-only"))
        assertEquals(null, pill(feed, "unused"))
        assertTrue(all.contains("href=\"/all?label=$onlyArchived\""))
        assertEquals("3", pill(all, "kotlin"))
        assertEquals("1", pill(all, "archived-only"))
        assertEquals(null, pill(all, "unused"))
    }

    @Test
    fun `a chosen label narrows the feed, hides the hero and keeps the filter while scrolling`() {
        val kotlin = label("kotlin")
        post("https://e.com/untagged", daysAgo = 90)
        (1..21).forEach { tag(post("https://e.com/k$it/", daysAgo = it), kotlin) }

        val html = page("/", "label" to "$kotlin")

        assertFalse(html.contains("id=\"hero\""))
        assertFalse(html.contains("https://e.com/untagged"))
        assertTrue(Regex("class=\"pill on\" href=\"/\\?label=$kotlin\"\\s+aria-current=\"page\"").containsMatchIn(html))
        assertTrue(html.contains("hx-get=\"/api/v1/posts/fragment?type=unread&amp;offset=20&amp;sort=newest&amp;label=$kotlin\""))

        val next = page("/api/v1/posts/fragment", "type" to "unread", "offset" to "20", "label" to "$kotlin")
        assertTrue(next.contains("https://e.com/k21/"))
        assertFalse(next.contains("https://e.com/untagged"))
    }

    @Test
    fun `garbage, missing, foreign or empty-here labels are ignored without a 400`() {
        val other = user(7102, """{"languageCode":"en"}""")
        val foreign = label("theirs", owner = other)
        val archivedOnly = label("archived-only")
        tag(post("https://e.com/a", archived = true), archivedOnly, archived = true)
        post("https://e.com/mine")

        listOf("abc", "", "-1", "99999999", "$foreign", "$archivedOnly").forEach { raw ->
            val html = page("/", "label" to raw)
            assertTrue(html.contains("https://e.com/mine"), "label=$raw")
            assertTrue(html.contains("id=\"hero\""), "label=$raw")
            assertFalse(html.contains("&amp;label="), "label=$raw")
        }
    }

    @Test
    fun `all filters archived posts by their own links when ids coincide`() {
        val kotlin = label("kotlin")
        post("https://e.com/unread-same-id", id = 777_777)
        tag(post("https://e.com/archived-same-id", archived = true, id = 777_777), kotlin, archived = true)

        val all = page("/all", "label" to "$kotlin")
        val feed = page("/", "label" to "$kotlin")

        assertTrue(all.contains("https://e.com/archived-same-id"))
        assertFalse(all.contains("https://e.com/unread-same-id"))
        // On the feed the label has no posts, so the filter is not offered and not applied.
        assertTrue(feed.contains("https://e.com/unread-same-id"))
    }

    // ── Date groups ──

    @Test
    fun `groups head the feed and the first heading is marked first`() {
        post("https://e.com/today")
        post("https://e.com/long-ago", daysAgo = 400)

        val html = page("/")

        assertTrue(html.contains("<h2 class=\"date-group first\">Today</h2>"))
        assertEquals(2, Regex("<h2 class=\"date-group").findAll(html).count())
    }

    @Test
    fun `the next page does not repeat the heading of a group it continues`() {
        // All saved today, minutes apart so the order is known: t1 is the newest.
        (1..25).forEach { i ->
            val id = post("https://e.com/t$i/")
            jdbcTemplate.update("UPDATE post SET created_date = now() - make_interval(mins => ?) WHERE id = ?", i, id)
        }

        val first = page("/")
        val next = page("/api/v1/posts/fragment", "type" to "unread", "offset" to "20")

        assertEquals(1, Regex("<h2 class=\"date-group").findAll(first).count())
        assertFalse(next.contains("date-group"))
        val cards = Regex("class=\"cell-title\" href=\"(https://e\\.com/t\\d+/)\"").findAll(next).map { it.groupValues[1] }.toList()
        assertEquals((21..25).map { "https://e.com/t$it/" }, cards)
    }

    @Test
    fun `the next page opens with a heading when its first post starts a new group`() {
        (1..20).forEach { post("https://e.com/t$it/") }
        post("https://e.com/old/", daysAgo = 400)

        val next = page("/api/v1/posts/fragment", "type" to "unread", "offset" to "20")

        assertTrue(Regex("<h2 class=\"date-group\">[A-Z][a-z]+ \\d{4}</h2>").containsMatchIn(next))
        assertFalse(next.contains("date-group first"))
    }

    @Test
    fun `archive has no date groups`() {
        post("https://e.com/a", archived = true)
        assertFalse(page("/archive").contains("date-group"))
    }

    // ── Age, title, hero ──

    @Test
    fun `unread cards show their age, archived cards on all keep the date`() {
        post("https://e.com/waiting", daysAgo = 42)
        post("https://e.com/archived", daysAgo = 3, archived = true)

        val html = page("/all")

        assertTrue(html.contains("class=\"age old\""))
        assertTrue(html.contains(">waiting 42 days</span>"))
        assertTrue(Regex("title=\"Saved \\d{2}\\.\\d{2}\\.\\d{4}\"").containsMatchIn(html))
        assertTrue(Regex("<span>\\d{2}\\.\\d{2}\\.\\d{4}</span>").containsMatchIn(html))
    }

    @Test
    fun `the hero shows its age as old next to the domain`() {
        post("https://e.com/fresh", daysAgo = 2)

        val html = page("/")
        val hero = html.substring(html.indexOf("id=\"hero\""), html.indexOf("class=\"page-head\""))

        assertTrue(hero.contains("<span class=\"age old\""))
        assertTrue(hero.contains(">waiting 2 days</span>"))
        assertFalse(hero.contains(" · waiting"))
    }

    @Test
    fun `a post without a title shows its url without scheme and trailing slash`() {
        post("https://martinfowler.com/articles/patterns-of-distributed-systems/", title = null)

        val html = page("/")

        assertTrue(html.contains(">martinfowler.com/<wbr>articles/<wbr>patterns-of-distributed-systems<"))
        assertFalse(html.contains(">https://martinfowler.com/articles/patterns-of-distributed-systems/<"))
    }
}
