package ru.proshik.pochitushki.repository

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

class PostDaoTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var postDao: PostDao

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (999, 'testuser', 'Test', 'User', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java
        )!!
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DELETE FROM archive_post")
        jdbcTemplate.execute("DELETE FROM post")
        jdbcTemplate.execute("DELETE FROM users")
    }

    @Test
    fun `addPost creates post with isFavorite false by default`() {
        val postId = postDao.addPost(PostStoreData("Test Title", "https://example.com", userId))

        val post = postDao.getPost(postId, userId, PostType.UNREAD)

        assertNotNull(post)
        assertFalse(post!!.isFavorite)
        assertEquals("Test Title", post.title)
        assertEquals("https://example.com", post.url)
    }

    @Test
    fun `toggleFavorite sets isFavorite to true then back to false`() {
        val postId = postDao.addPost(PostStoreData("Test Title", "https://example.com", userId))

        val afterFirstToggle = postDao.toggleFavorite(postId, userId, PostType.UNREAD)
        assertTrue(afterFirstToggle)

        val post = postDao.getPost(postId, userId, PostType.UNREAD)
        assertTrue(post!!.isFavorite)

        val afterSecondToggle = postDao.toggleFavorite(postId, userId, PostType.UNREAD)
        assertFalse(afterSecondToggle)

        val postAfterSecond = postDao.getPost(postId, userId, PostType.UNREAD)
        assertFalse(postAfterSecond!!.isFavorite)
    }

    @Test
    fun `getPosts with FAVORITES type returns only favorite posts`() {
        val favPostId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postDao.addPost(PostStoreData("Regular Post", "https://regular.com", userId))

        postDao.toggleFavorite(favPostId, userId, PostType.UNREAD)

        val favorites = postDao.getPosts(userId, PostType.FAVORITES, 10, 0)

        assertEquals(1, favorites.size)
        assertEquals(favPostId, favorites[0].id)
        assertTrue(favorites[0].isFavorite)
    }

    @Test
    fun `getPostCount with FAVORITES type counts only favorite posts`() {
        val postId1 = postDao.addPost(PostStoreData("Post 1", "https://one.com", userId))
        postDao.addPost(PostStoreData("Post 2", "https://two.com", userId))
        postDao.addPost(PostStoreData("Post 3", "https://three.com", userId))

        postDao.toggleFavorite(postId1, userId, PostType.UNREAD)

        assertEquals(1, postDao.getPostCount(userId, PostType.FAVORITES))
        assertEquals(3, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `addToArchivePost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postDao.toggleFavorite(postId, userId, PostType.UNREAD)

        postDao.addToArchivePost(postId, userId)
        postDao.deletePost(postId, userId, PostType.UNREAD)

        val archiveCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM archive_post WHERE user_id = ? AND is_favorite = true", Int::class.java, userId
        )!!
        assertEquals(1, archiveCount)
    }

    @Test
    fun `addToUnreadPost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postDao.toggleFavorite(postId, userId, PostType.UNREAD)
        postDao.addToArchivePost(postId, userId)
        postDao.deletePost(postId, userId, PostType.UNREAD)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postDao.addToUnreadPost(archivePostId, userId)
        postDao.deletePost(archivePostId, userId, PostType.ARCHIVE)

        val unreadPosts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(1, unreadPosts.size)
        assertTrue(unreadPosts[0].isFavorite)
    }

    @Test
    fun `getPost returns null for non-existing post`() {
        val result = postDao.getPost(999999L, userId, PostType.UNREAD)
        assertNull(result)
    }

    @Test
    fun `toggleFavorite on archive_post table works correctly`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://example.com", userId))
        postDao.addToArchivePost(postId, userId)
        postDao.deletePost(postId, userId, PostType.UNREAD)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        val newState = postDao.toggleFavorite(archivePostId, userId, PostType.ARCHIVE)
        assertTrue(newState)

        val archivePost = postDao.getPost(archivePostId, userId, PostType.ARCHIVE)
        assertTrue(archivePost!!.isFavorite)
    }

    @Test
    fun `addPost creates post and returns id`() {
        val postId = postDao.addPost(PostStoreData("My Title", "https://test.com/page", userId))

        assertTrue(postId > 0)
        val post = postDao.getPost(postId, userId, PostType.UNREAD)
        assertNotNull(post)
        assertEquals("My Title", post!!.title)
        assertEquals("https://test.com/page", post.url)
        assertEquals(userId, post.userId)
    }

    @Test
    fun `deletePost removes post`() {
        val postId = postDao.addPost(PostStoreData("To Delete", "https://delete.com", userId))

        postDao.deletePost(postId, userId, PostType.UNREAD)

        assertNull(postDao.getPost(postId, userId, PostType.UNREAD))
    }

    @Test
    fun `getPosts returns posts with pagination`() {
        postDao.addPost(PostStoreData("Post A", "https://a.com", userId))
        postDao.addPost(PostStoreData("Post B", "https://b.com", userId))
        postDao.addPost(PostStoreData("Post C", "https://c.com", userId))
        postDao.addPost(PostStoreData("Post D", "https://d.com", userId))

        val firstPage = postDao.getPosts(userId, PostType.UNREAD, 2, 0)
        assertEquals(2, firstPage.size)

        val secondPage = postDao.getPosts(userId, PostType.UNREAD, 2, 2)
        assertEquals(2, secondPage.size)

        val allUrls = (firstPage + secondPage).map { it.url }.toSet()
        assertEquals(4, allUrls.size)
    }

    @Test
    fun `getPosts returns empty list when no posts`() {
        val posts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `findPost finds post by URL`() {
        postDao.addPost(PostStoreData("Found", "https://find-me.com/article", userId))
        postDao.addPost(PostStoreData("Other", "https://other.com", userId))

        val found = postDao.findPost(userId, PostType.UNREAD, "https://find-me.com/article")
        assertEquals(1, found.size)
        assertEquals("Found", found[0].title)
    }

    @Test
    fun `findPost returns empty for non-existing URL`() {
        postDao.addPost(PostStoreData("Exists", "https://exists.com", userId))

        val found = postDao.findPost(userId, PostType.UNREAD, "https://nonexistent.com")
        assertTrue(found.isEmpty())
    }

    @Test
    fun `getPostCount returns correct count`() {
        assertEquals(0, postDao.getPostCount(userId, PostType.UNREAD))

        postDao.addPost(PostStoreData("P1", "https://one.com", userId))
        postDao.addPost(PostStoreData("P2", "https://two.com", userId))

        assertEquals(2, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `getRandomPost returns post for user`() {
        postDao.addPost(PostStoreData("Random Post", "https://random.com", userId))

        val random = postDao.getRandomPost(userId)
        assertNotNull(random)
        assertEquals("Random Post", random!!.title)
    }

    @Test
    fun `getRandomPost returns null when no posts`() {
        val random = postDao.getRandomPost(userId)
        assertNull(random)
    }

    @Test
    fun `addToArchivePost moves post to archive table`() {
        val postId = postDao.addPost(PostStoreData("Archive Me", "https://archive.com", userId))

        postDao.addToArchivePost(postId, userId)
        postDao.deletePost(postId, userId, PostType.UNREAD)

        assertNull(postDao.getPost(postId, userId, PostType.UNREAD))
        val archiveCount = postDao.getPostCount(userId, PostType.ARCHIVE)
        assertEquals(1, archiveCount)
    }

    @Test
    fun `addToUnreadPost moves post back from archive`() {
        val postId = postDao.addPost(PostStoreData("Unread Me", "https://unread.com", userId))
        postDao.addToArchivePost(postId, userId)
        postDao.deletePost(postId, userId, PostType.UNREAD)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postDao.addToUnreadPost(archivePostId, userId)
        postDao.deletePost(archivePostId, userId, PostType.ARCHIVE)

        assertEquals(0, postDao.getPostCount(userId, PostType.ARCHIVE))
        assertEquals(1, postDao.getPostCount(userId, PostType.UNREAD))
    }
}
