package ru.proshik.pochitushki.repository

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import ru.proshik.pochitushki.BaseIntegrationTest
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType

class LabelDaoTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var labelDao: LabelDao

    @Autowired
    private lateinit var postDao: PostDao

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var userId: Long = 0L
    private var otherUserId: Long = 0L

    @BeforeEach
    fun setUp() {
        userId = insertUser(5101, "labeluser")
        otherUserId = insertUser(5102, "labelother")
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

    private fun insertUser(telegramId: Long, username: String): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO users(telegram_id, username, first_name, last_name, settings)
               VALUES (?, ?, 'Label', 'User', '{"languageCode":"en","tgFeedEntriesNumber":3}'::jsonb)
               RETURNING id""",
            Long::class.java, telegramId, username
        )!!

    @Test
    fun `createLabel returns the label with an id`() {
        val label = labelDao.createLabel(userId, "kotlin")

        assertTrue(label.id > 0)
        assertEquals("kotlin", label.name)
    }

    @Test
    fun `creating the same name twice returns the same row instead of failing`() {
        val first = labelDao.createLabel(userId, "kotlin")
        val second = labelDao.createLabel(userId, "kotlin")

        assertEquals(first.id, second.id)
        assertEquals(1, labelDao.getLabels(userId).size)
    }

    @Test
    fun `the same name belongs to each user separately`() {
        val mine = labelDao.createLabel(userId, "kotlin")
        val theirs = labelDao.createLabel(otherUserId, "kotlin")

        assertTrue(mine.id != theirs.id)
        assertEquals(1, labelDao.getLabels(userId).size)
    }

    @Test
    fun `getLabels returns the user's labels sorted by name`() {
        labelDao.createLabel(userId, "zebra")
        labelDao.createLabel(userId, "alpha")
        labelDao.createLabel(otherUserId, "not mine")

        assertEquals(listOf("alpha", "zebra"), labelDao.getLabels(userId).map { it.name })
    }

    @Test
    fun `findLabel does not return another user's label`() {
        val theirs = labelDao.createLabel(otherUserId, "theirs")

        assertNull(labelDao.findLabel(theirs.id, userId))
    }

    @Test
    fun `deleteLabel refuses another user's label`() {
        val theirs = labelDao.createLabel(otherUserId, "theirs")

        assertEquals(0, labelDao.deleteLabel(theirs.id, userId))
        assertEquals(1, labelDao.getLabels(otherUserId).size)
    }

    @Test
    fun `attachLabel links the post and shows up in the lookup`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://a.com", userId))
        val label = labelDao.createLabel(userId, "kotlin")

        assertTrue(labelDao.attachLabel(postId, label.id, userId, LabelTarget.UNREAD))

        val found = labelDao.findLabelsForPosts(listOf(postId), emptyList())
        assertEquals(listOf("kotlin"), found[LabelTarget.UNREAD to postId]?.map { it.name })
    }

    @Test
    fun `attaching twice is a no-op rather than a duplicate`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://a.com", userId))
        val label = labelDao.createLabel(userId, "kotlin")

        assertTrue(labelDao.attachLabel(postId, label.id, userId, LabelTarget.UNREAD))
        assertFalse(labelDao.attachLabel(postId, label.id, userId, LabelTarget.UNREAD))

        assertEquals(1, labelDao.findLabelsForPosts(listOf(postId), emptyList())[LabelTarget.UNREAD to postId]?.size)
    }

    @Test
    fun `attachLabel refuses to link another user's post`() {
        val theirPostId = postDao.addPost(PostStoreData("Theirs", "https://b.com", otherUserId))
        val label = labelDao.createLabel(userId, "kotlin")

        assertFalse(labelDao.attachLabel(theirPostId, label.id, userId, LabelTarget.UNREAD))
        assertTrue(labelDao.findLabelsForPosts(listOf(theirPostId), emptyList()).isEmpty())
    }

    @Test
    fun `attachLabel refuses to link another user's label`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://a.com", userId))
        val theirLabel = labelDao.createLabel(otherUserId, "theirs")

        assertFalse(labelDao.attachLabel(postId, theirLabel.id, userId, LabelTarget.UNREAD))
    }

    @Test
    fun `detachLabel removes the link and refuses another user's post`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://a.com", userId))
        val label = labelDao.createLabel(userId, "kotlin")
        labelDao.attachLabel(postId, label.id, userId, LabelTarget.UNREAD)

        assertFalse(labelDao.detachLabel(postId, label.id, otherUserId, LabelTarget.UNREAD))
        assertTrue(labelDao.detachLabel(postId, label.id, userId, LabelTarget.UNREAD))
        assertTrue(labelDao.findLabelsForPosts(listOf(postId), emptyList()).isEmpty())
    }

    @Test
    fun `deleting a label unlinks it from posts`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://a.com", userId))
        val label = labelDao.createLabel(userId, "kotlin")
        labelDao.attachLabel(postId, label.id, userId, LabelTarget.UNREAD)

        assertEquals(1, labelDao.deleteLabel(label.id, userId))

        assertTrue(labelDao.findLabelsForPosts(listOf(postId), emptyList()).isEmpty())
    }

    @Test
    fun `lookup keys are per table, so colliding ids do not mix labels up`() {
        // post and archive_post have separate sequences: the same id in both is routine.
        jdbcTemplate.update(
            "INSERT INTO post(id, title, url, user_id) VALUES (9001, 'Unread', 'https://u.com', ?)", userId
        )
        jdbcTemplate.update(
            "INSERT INTO archive_post(id, title, url, user_id) VALUES (9001, 'Archived', 'https://a.com', ?)", userId
        )
        val unreadLabel = labelDao.createLabel(userId, "on-shelf")
        val archiveLabel = labelDao.createLabel(userId, "read-already")
        labelDao.attachLabel(9001, unreadLabel.id, userId, LabelTarget.UNREAD)
        labelDao.attachLabel(9001, archiveLabel.id, userId, LabelTarget.ARCHIVE)

        val found = labelDao.findLabelsForPosts(listOf(9001), listOf(9001))

        assertEquals(listOf("on-shelf"), found[LabelTarget.UNREAD to 9001L]?.map { it.name })
        assertEquals(listOf("read-already"), found[LabelTarget.ARCHIVE to 9001L]?.map { it.name })
    }

    @Test
    fun `findLabelsForPosts returns empty without querying when there are no ids`() {
        assertTrue(labelDao.findLabelsForPosts(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `copyLabels carries the links onto the row a move created`() {
        val postId = postDao.addPost(PostStoreData("Post", "https://a.com", userId))
        val label = labelDao.createLabel(userId, "kotlin")
        labelDao.attachLabel(postId, label.id, userId, LabelTarget.UNREAD)
        val archiveId = postDao.addToArchivePost(postId, userId)!!

        labelDao.copyLabels(postId, archiveId, LabelTarget.UNREAD, LabelTarget.ARCHIVE)
        postDao.deletePost(postId, userId, PostType.UNREAD)

        val found = labelDao.findLabelsForPosts(emptyList(), listOf(archiveId))
        assertEquals(listOf("kotlin"), found[LabelTarget.ARCHIVE to archiveId]?.map { it.name })
    }
}
