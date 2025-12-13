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

    fun addPost(url: URL, userId: Long): String? {
        val urlString = url.toString()
        val title = loadTitle(urlString)

        val postStoreData = PostStoreData(title, urlString, userId)

        postDao.addPost(postStoreData)

        return title
    }

    fun getPosts(userId: Long, postType: PostType, limit: Int, offset: Int): List<PostData> {
        return postDao.getPosts(userId, postType, limit, offset)
    }

    fun findPost(userId: Long, postType: PostType, url: URL): PostData? {
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
        postDao.deletePost(postId, postType)
    }

    @Transactional
    fun archivePost(postId: Long) {
        postDao.addToArchivePost(postId)
        postDao.deletePost(postId, PostType.UNREAD)
    }

    @Transactional
    fun unreadPost(postId: Long) {
        postDao.addToUnreadPost(postId)
        postDao.deletePost(postId, PostType.ARCHIVE)
    }

    fun getRandomPost(userId: Long): PostData? {
        return postDao.getRandomPost(userId)
    }

    fun getPostCount(userId: Long, postType: PostType): Int {
        return postDao.getPostCount(userId, postType)
    }
}