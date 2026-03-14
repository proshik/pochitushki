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

        val post = postDao.getPost(postId, PostType.UNREAD)

        assertNotNull(post)
        assertFalse(post!!.isFavorite)
        assertEquals("Test Title", post.title)
        assertEquals("https://example.com", post.url)
    }

    @Test
    fun `toggleFavorite sets isFavorite to true then back to false`() {
        val postId = postDao.addPost(PostStoreData("Test Title", "https://example.com", userId))

        val afterFirstToggle = postDao.toggleFavorite(postId, PostType.UNREAD)
        assertTrue(afterFirstToggle)

        val post = postDao.getPost(postId, PostType.UNREAD)
        assertTrue(post!!.isFavorite)

        val afterSecondToggle = postDao.toggleFavorite(postId, PostType.UNREAD)
        assertFalse(afterSecondToggle)

        val postAfterSecond = postDao.getPost(postId, PostType.UNREAD)
        assertFalse(postAfterSecond!!.isFavorite)
    }

    @Test
    fun `getPosts with FAVORITES type returns only favorite posts`() {
        val favPostId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postDao.addPost(PostStoreData("Regular Post", "https://regular.com", userId))

        postDao.toggleFavorite(favPostId, PostType.UNREAD)

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

        postDao.toggleFavorite(postId1, PostType.UNREAD)

        assertEquals(1, postDao.getPostCount(userId, PostType.FAVORITES))
        assertEquals(3, postDao.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `addToArchivePost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postDao.toggleFavorite(postId, PostType.UNREAD)

        postDao.addToArchivePost(postId)
        postDao.deletePost(postId, PostType.UNREAD)

        val archiveCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM archive_post WHERE user_id = ? AND is_favorite = true", Int::class.java, userId
        )!!
        assertEquals(1, archiveCount)
    }

    @Test
    fun `addToUnreadPost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postDao.toggleFavorite(postId, PostType.UNREAD)
        postDao.addToArchivePost(postId)
        postDao.deletePost(postId, PostType.UNREAD)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postDao.addToUnreadPost(archivePostId)
        postDao.deletePost(archivePostId, PostType.ARCHIVE)

        val unreadPosts = postDao.getPosts(userId, PostType.UNREAD, 10, 0)
        assertEquals(1, unreadPosts.size)
        assertTrue(unreadPosts[0].isFavorite)
    }

    @Test
    fun `getPost returns null for non-existing post`() {
        val result = postDao.getPost(999999L, PostType.UNREAD)
        assertNull(result)
    }

    @Test
    fun `toggleFavorite on archive_post table works correctly`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://example.com", userId))
        postDao.addToArchivePost(postId)
        postDao.deletePost(postId, PostType.UNREAD)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        val newState = postDao.toggleFavorite(archivePostId, PostType.ARCHIVE)
        assertTrue(newState)

        val archivePost = postDao.getPost(archivePostId, PostType.ARCHIVE)
        assertTrue(archivePost!!.isFavorite)
    }
}
