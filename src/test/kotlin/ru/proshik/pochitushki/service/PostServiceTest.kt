package ru.proshik.pochitushki.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @BeforeEach
    fun setUp() {
        userId = jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (998, 'svcuser', 'Svc', 'User', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
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
    fun `toggleFavorite returns true on first call and false on second`() {
        val postId = postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val first = postService.toggleFavorite(postId, PostType.UNREAD)
        assertTrue(first)

        val second = postService.toggleFavorite(postId, PostType.UNREAD)
        assertFalse(second)
    }

    @Test
    fun `getPost returns post with correct isFavorite state`() {
        val postId = postDao.addPost(PostStoreData("Title", "https://example.com", userId))

        val beforeToggle = postService.getPost(postId, PostType.UNREAD)
        assertFalse(beforeToggle!!.isFavorite)

        postService.toggleFavorite(postId, PostType.UNREAD)

        val afterToggle = postService.getPost(postId, PostType.UNREAD)
        assertTrue(afterToggle!!.isFavorite)
    }

    @Test
    fun `getPosts with FAVORITES returns only favorites`() {
        val favId = postDao.addPost(PostStoreData("Fav", "https://fav.com", userId))
        postDao.addPost(PostStoreData("Regular", "https://regular.com", userId))

        postService.toggleFavorite(favId, PostType.UNREAD)

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

        postService.toggleFavorite(id1, PostType.UNREAD)
        postService.toggleFavorite(id2, PostType.UNREAD)

        assertEquals(2, postService.getPostCount(userId, PostType.FAVORITES))
        assertEquals(3, postService.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `archivePost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postService.toggleFavorite(postId, PostType.UNREAD)

        postService.archivePost(postId)

        val archiveCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM archive_post WHERE user_id = ? AND is_favorite = true", Int::class.java, userId
        )!!
        assertEquals(1, archiveCount)
        assertEquals(0, postService.getPostCount(userId, PostType.UNREAD))
    }

    @Test
    fun `unreadPost preserves is_favorite flag`() {
        val postId = postDao.addPost(PostStoreData("Fav Post", "https://fav.com", userId))
        postService.toggleFavorite(postId, PostType.UNREAD)
        postService.archivePost(postId)

        val archivePostId = jdbcTemplate.queryForObject(
            "SELECT id FROM archive_post WHERE user_id = ?", Long::class.java, userId
        )!!

        postService.unreadPost(archivePostId)

        val favorites = postService.getPosts(userId, PostType.FAVORITES, 10, 0)
        assertEquals(1, favorites.size)
        assertTrue(favorites[0].isFavorite)
    }
}
