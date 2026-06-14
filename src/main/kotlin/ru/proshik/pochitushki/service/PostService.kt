package ru.proshik.pochitushki.service

import java.io.IOException
import java.net.URL
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao


@Service
class PostService(
    private val postDao: PostDao,
    private val urlSecurityValidator: UrlSecurityValidator,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun addPost(url: URL, userId: Long): Pair<Long, String?> {
        logger.debug("User {} adding post: {}", userId, url)

        val urlString = url.toString()
        // SSRF guard: refuse to store/fetch URLs that target internal/non-public addresses.
        urlSecurityValidator.validate(urlString)
        val title = loadTitle(urlString)

        val postStoreData = PostStoreData(title, urlString, userId)

        val addedPostId = postDao.addPost(postStoreData)

        return Pair(addedPostId, title)
    }

    fun getPosts(userId: Long, postType: PostType, limit: Int, offset: Int): List<PostData> {
        return postDao.getPosts(userId, postType, limit, offset)
    }

    fun findPost(userId: Long, postType: PostType, url: URL): List<PostData> {
        return postDao.findPost(userId, postType, url.toString())
    }

    private fun loadTitle(url: String): String? {
        val title = try {
            // followRedirects(false): a public URL must not 3xx-redirect into an internal address
            // after the SSRF check. timeout: avoid hanging the request thread on a slow/hostile host.
            val doc: Document = Jsoup.connect(url)
                .followRedirects(false)
                .timeout(5000)
                .get()
            return doc.title()
        } catch (e: IOException) {
            logger.warn("Failed to load title: $url", e)
            null
        }

        return title
    }

    fun deletePost(postId: Long, userId: Long, postType: PostType): Int {
        logger.debug("Deleting post {} for user {} (type={})", postId, userId, postType)
        return postDao.deletePost(postId, userId, postType)
    }

    @Transactional
    fun archivePost(postId: Long, userId: Long): Long {
        logger.debug("Archiving post {} for user {}", postId, userId)
        val newId = postDao.addToArchivePost(postId, userId)
        postDao.deletePost(postId, userId, PostType.UNREAD)
        return newId
    }

    @Transactional
    fun unreadPost(postId: Long, userId: Long): Long {
        logger.debug("Moving post {} to unread for user {}", postId, userId)
        val newId = postDao.addToUnreadPost(postId, userId)
        postDao.deletePost(postId, userId, PostType.ARCHIVE)
        return newId
    }

    fun getRandomPost(userId: Long): PostData? {
        return postDao.getRandomPost(userId)
    }

    fun getPostCount(userId: Long, postType: PostType): Int {
        return postDao.getPostCount(userId, postType)
    }

    fun toggleFavorite(postId: Long, userId: Long, postType: PostType): Boolean {
        val newValue = postDao.toggleFavorite(postId, userId, postType)
        logger.debug("Toggled favorite for post {} user {} (type={}), now={}", postId, userId, postType, newValue)
        return newValue
    }

    fun getPost(postId: Long, userId: Long, postType: PostType): PostData? {
        return postDao.getPost(postId, userId, postType)
    }
}