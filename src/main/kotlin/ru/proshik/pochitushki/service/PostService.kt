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
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun addPost(url: URL, userId: Long): Pair<Long, String?> {
        logger.debug("User {} adding post: {}", userId, url)
        val urlString = url.toString()
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
            // Connect to the URL and parse the HTML document
            val doc: Document = Jsoup.connect(url).get()
            // Get the title element's text
            return doc.title()
        } catch (e: IOException) {
            logger.warn("Failed to load title: $url", e)
            null
        }

        return title
    }

    fun deletePost(postId: Long, postType: PostType) {
        logger.debug("Deleting post {} (type={})", postId, postType)
        postDao.deletePost(postId, postType)
    }

    @Transactional
    fun archivePost(postId: Long): Long {
        logger.debug("Archiving post {}", postId)
        val newId = postDao.addToArchivePost(postId)
        postDao.deletePost(postId, PostType.UNREAD)
        return newId
    }

    @Transactional
    fun unreadPost(postId: Long): Long {
        logger.debug("Moving post {} to unread", postId)
        val newId = postDao.addToUnreadPost(postId)
        postDao.deletePost(postId, PostType.ARCHIVE)
        return newId
    }

    fun getRandomPost(userId: Long): PostData? {
        return postDao.getRandomPost(userId)
    }

    fun getPostCount(userId: Long, postType: PostType): Int {
        return postDao.getPostCount(userId, postType)
    }

    fun toggleFavorite(postId: Long, postType: PostType): Boolean {
        val newValue = postDao.toggleFavorite(postId, postType)
        logger.debug("Toggled favorite for post {} (type={}), now={}", postId, postType, newValue)
        return newValue
    }

    fun getPost(postId: Long, postType: PostType): PostData? {
        return postDao.getPost(postId, postType)
    }
}