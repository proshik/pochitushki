package ru.proshik.pochitushki.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import java.net.URI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao

class PostServiceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var postService: PostService

    @Autowired
    private lateinit var postDao: PostDao

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L

    private lateinit var wireMockExternalUrl: WireMockServer

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (998, 'svcuser', 'Svc', 'User', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java
        )!!

        wireMockExternalUrl = WireMockServer(wireMockConfig().dynamicPort())
        wireMockExternalUrl.start()
    }

    @AfterEach
    fun tearDown() {
        wireMockExternalUrl.stop()
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    @Test
    fun `toggleFavorite returns true on first call and false on second`() {
        val postId = postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val first = postService.toggleFavorite(postId, userId, PostType.UNREAD)
        assertTrue(first!!)

        val second = postService.toggleFavorite(postId, userId, PostType.UNREAD)
        assertFalse(second!!)
    }

    @Test
    fun `getPost returns post with correct isFavorite state`() {
        val postId = postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val beforeToggle = postService.getPost(postId, userId, PostType.UNREAD)
        assertFalse(beforeToggle!!.isFavorite)

        postService.toggleFavorite(postId, userId, PostType.UNREAD)

        val afterToggle = postService.getPost(postId, userId, PostType.UNREAD)
        assertTrue(afterToggle!!.isFavorite)
    }

    @Test
    fun `getPosts with FAVORITES returns only favorites`() {
        val favId = postDao.addPost(PostStoreData("Fav", "https://fav.com", userId))
        postDao.addPost(PostStoreData("Regular", "https://regular.com", userId))

        postService.toggleFavorite(favId, userId, PostType.UNREAD)

        val favorites = postService.getPosts(userId, PostType.FAVORITES, 10, 0)
        assertEquals(1, favorites.size)
        assertEquals(favId, favorites[0].id)
        assertTrue(favorites[0].isFavorite)
    }

    @Test
    fun `getPostCount for FAVORITES counts only favorited posts`() {
        val id1 = postDao.addPost(PostStoreData("Post 1", "https://one.com", userId))
        val id2 = postDao.addPost(PostStoreData("Post 2", "https://two.com", userId))
        postDao.addPost(PostStoreData("Post 3", "https://three.com", userId))

        postService.toggleFavorite(id1, userId, PostType.UNREAD)
        postService.toggleFavorite(id2, userId, PostType.UNREAD)

        assertEquals(2, postService.getPostCount(userId, PostType.FAVORITES))
        assertEquals(3, postService.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `archivePost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postService.toggleFavorite(postId, userId, PostType.UNREAD)

        postService.archivePost(postId, userId)

        val archiveCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM archive_post WHERE user_id = ? AND is_favorite = true", Int::class.java, userId
        )!!
        assertEquals(1, archiveCount)
        assertEquals(0, postService.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `unreadPost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postService.toggleFavorite(postId, userId, PostType.UNREAD)
        postService.archivePost(postId, userId)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postService.unreadPost(archivePostId, userId)

        val favorites = postService.getPosts(userId, PostType.FAVORITES, 10, 0)
        assertEquals(1, favorites.size)
        assertTrue(favorites[0].isFavorite)
    }

    @Test
    fun `addPost saves post and returns id with title`() {
        wireMockExternalUrl.stubFor(
            get(urlEqualTo("/page")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody("<html><head><title>Page Title</title></head><body></body></html>")
            )
        )

        val url = URI.create("http://localhost:${wireMockExternalUrl.port()}/page").toURL()
        val (postId, title) = postService.addPost(url, userId)

        assertTrue(postId > 0)
        assertEquals("Page Title", title)

        val post = postService.getPost(postId, userId, PostType.UNREAD)
        assertNotNull(post)
        assertEquals("Page Title", post!!.title)
    }

    @Test
    fun `addPost handles URL without title gracefully`() {
        wireMockExternalUrl.stubFor(
            get(urlEqualTo("/no-title")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html; charset=utf-8")
                    .withBody("<html><head></head><body>No title</body></html>")
            )
        )

        val url = URI.create("http://localhost:${wireMockExternalUrl.port()}/no-title").toURL()
        val (postId, title) = postService.addPost(url, userId)

        assertTrue(postId > 0)
        assertNotNull(title)
    }

    @Test
    fun `getPosts returns posts ordered by creation desc`() {
        postDao.addPost(PostStoreData("First", "https://first.com", userId))
        Thread.sleep(10)
        postDao.addPost(PostStoreData("Second", "https://second.com", userId))

        val posts = postService.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(2, posts.size)
        assertEquals("Second", posts[0].title)
        assertEquals("First", posts[1].title)
    }

    @Test
    fun `deletePost removes post from unread`() {
        val postId = postDao.addPost(PostStoreData("To Delete", "https://del.com", userId))

        postService.deletePost(postId, userId, PostType.UNREAD)

        assertNull(postService.getPost(postId, userId, PostType.UNREAD))
    }

    @Test
    fun `deletePost removes post from archive`() {
        val postId = postDao.addPost(PostStoreData("To Archive", "https://arch.com", userId))
        postService.archivePost(postId, userId)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postService.deletePost(archivePostId, userId, PostType.ARCHIVE)

        assertNull(postService.getPost(archivePostId, userId, PostType.ARCHIVE))
    }

    @Test
    fun `archivePost moves post from unread to archive`() {
        val postId = postDao.addPost(PostStoreData("Move Me", "https://move.com", userId))

        postService.archivePost(postId, userId)

        assertNull(postService.getPost(postId, userId, PostType.UNREAD))
        assertEquals(1, postService.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `unreadPost moves post from archive to unread`() {
        val postId = postDao.addPost(PostStoreData("Back", "https://back.com", userId))
        postService.archivePost(postId, userId)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postService.unreadPost(archivePostId, userId)

        assertNull(postService.getPost(archivePostId, userId, PostType.ARCHIVE))
        assertEquals(1, postService.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `getRandomPost returns random unread post`() {
        postDao.addPost(PostStoreData("Random", "https://random.com", userId))

        val random = postService.getRandomPost(userId)
        assertNotNull(random)
        assertEquals("Random", random!!.title)
    }

    @Test
    fun `getRandomPost returns null when empty`() {
        val random = postService.getRandomPost(userId)
        assertNull(random)
    }

    @Test
    fun `getPostCount returns correct count for each PostType`() {
        postDao.addPost(PostStoreData("U1", "https://u1.com", userId))
        val u2 = postDao.addPost(PostStoreData("U2", "https://u2.com", userId))
        postService.archivePost(u2, userId)

        assertEquals(1, postService.getPostCount(userId, PostType.UNREAD))
        assertEquals(1, postService.getPostCount(userId, PostType.ARCHIVE))
    }

    @Test
    fun `findPost returns existing post by URL`() {
        postDao.addPost(PostStoreData("Found", "https://find.com/article", userId))

        val url = URI.create("https://find.com/article").toURL()
        val found = postService.findPost(userId, PostType.UNREAD, url)
        assertEquals(1, found.size)
        assertEquals("Found", found[0].title)
    }

    @Test
    fun `findPost returns empty for unknown URL`() {
        val url = URI.create("https://unknown.com").toURL()
        val found = postService.findPost(userId, PostType.UNREAD, url)
        assertTrue(found.isEmpty())
    }
}
