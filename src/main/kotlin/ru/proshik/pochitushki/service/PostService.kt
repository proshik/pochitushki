package ru.proshik.pochitushki.service

import java.io.IOException
import java.net.URL
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao


/**
 * Two moves of the same post raced and this one lost: its copy was rolled back, the winner's
 * copy is on the far shelf. Callers should treat it as "already moved", not as a failure.
 */
class ConcurrentPostMoveException(postId: Long) : RuntimeException("Post $postId was moved concurrently")

@Service
class PostService(
    private val postDao: PostDao,
    private val urlSecurityValidator: UrlSecurityValidator,
    private val labelService: LabelService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun addPost(url: URL, userId: Long): Pair<Long, String?> {
        logger.debug("User {} adding post: {}", userId, url)

        val urlString = url.toString()
        // SSRF guard: refuse to store/fetch URLs that target internal/non-public addresses.
        urlSecurityValidator.validate(urlString)
        val meta = loadPageMeta(urlString)

        val postStoreData = PostStoreData(meta.title, urlString, userId, ogImageUrl = meta.ogImage)

        val addedPostId = postDao.addPost(postStoreData)

        return Pair(addedPostId, meta.title)
    }

    fun getPosts(userId: Long, postType: PostType, limit: Int, offset: Int): List<PostData> {
        return labelService.withLabels(postDao.getPosts(userId, postType, limit, offset))
    }

    /** Every post carrying the label, on either shelf. */
    fun getPostsByLabel(userId: Long, labelId: Long, limit: Int, offset: Int): List<PostData> {
        return labelService.withLabels(postDao.getPostsByLabel(userId, labelId, limit, offset))
    }

    fun getPostCountByLabel(userId: Long, labelId: Long): Int {
        return postDao.getPostCountByLabel(userId, labelId)
    }

    fun findPost(userId: Long, postType: PostType, url: URL): List<PostData> {
        return labelService.withLabels(postDao.findPost(userId, postType, url.toString()))
    }

    private data class PageMeta(val title: String?, val ogImage: String?)

    private fun loadPageMeta(url: String): PageMeta {
        return try {
            // followRedirects(false): a public URL must not 3xx-redirect into an internal address
            // after the SSRF check. timeout: avoid hanging the request thread on a slow/hostile host.
            val doc: Document = Jsoup.connect(url)
                .followRedirects(false)
                .timeout(5000)
                .get()
            PageMeta(doc.title().takeIf { it.isNotBlank() }, extractOgImage(doc))
        } catch (e: IOException) {
            logger.warn("Failed to load page meta: $url", e)
            PageMeta(null, null)
        }
    }

    // Only absolute https urls of sane length: the value is later rendered as an <img src>
    // for the cover jacket, so relative/insecure/oversized values are dropped, not fixed up.
    private fun extractOgImage(doc: Document): String? =
        doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.startsWith("https://") && it.length <= 2000 }

    fun deletePost(postId: Long, userId: Long, postType: PostType): Int {
        logger.debug("Deleting post {} for user {} (type={})", postId, userId, postType)
        return postDao.deletePost(postId, userId, postType)
    }

    /** Returns the new archive-post id, or null if the user has no such unread post. */
    @Transactional
    fun archivePost(postId: Long, userId: Long): Long? {
        logger.debug("Archiving post {} for user {}", postId, userId)
        val newId = postDao.addToArchivePost(postId, userId) ?: return null
        // Before the delete: the source links go with the row on ON DELETE CASCADE.
        labelService.copyLabelsOnMove(postId, newId, LabelTarget.UNREAD, LabelTarget.ARCHIVE)
        requireRowMoved(postDao.deletePost(postId, userId, PostType.UNREAD), postId)
        return newId
    }

    /** Returns the new unread-post id, or null if the user has no such archive post. */
    @Transactional
    fun unreadPost(postId: Long, userId: Long): Long? {
        logger.debug("Moving post {} to unread for user {}", postId, userId)
        val newId = postDao.addToUnreadPost(postId, userId) ?: return null
        labelService.copyLabelsOnMove(postId, newId, LabelTarget.ARCHIVE, LabelTarget.UNREAD)
        requireRowMoved(postDao.deletePost(postId, userId, PostType.ARCHIVE), postId)
        return newId
    }

    /**
     * A move is insert-then-delete, and the two halves are not one atomic statement: two clicks
     * (or the bot and the web at once) can both copy the row before either deletes it, leaving
     * two copies on the far shelf. The loser of that race deletes nothing — so if the delete
     * touched no row, the transaction is rolled back and its insert goes with it.
     */
    private fun requireRowMoved(deleted: Int, postId: Long) {
        if (deleted == 0) {
            logger.info("Concurrent move detected for post {} — rolling this one back", postId)
            throw ConcurrentPostMoveException(postId)
        }
    }

    fun getRandomPost(userId: Long): PostData? {
        return postDao.getRandomPost(userId)?.let { labelService.withLabels(it) }
    }

    /** The oldest unread post — the web feed's "next to read" hero. */
    fun getOldestUnreadPost(userId: Long): PostData? {
        return postDao.getOldestPost(userId)?.let { labelService.withLabels(it) }
    }

    /** A reshuffled handful of unread posts — the /random shelf. */
    fun getRandomPosts(userId: Long, count: Int): List<PostData> {
        return labelService.withLabels(postDao.getRandomPosts(userId, count))
    }

    fun getPostCount(userId: Long, postType: PostType): Int {
        return postDao.getPostCount(userId, postType)
    }

    /** Returns the new favorite flag, or null if the user has no such post. */
    fun toggleFavorite(postId: Long, userId: Long, postType: PostType): Boolean? {
        val newValue = postDao.toggleFavorite(postId, userId, postType)
        logger.debug("Toggled favorite for post {} user {} (type={}), now={}", postId, userId, postType, newValue)
        return newValue
    }

    fun getPost(postId: Long, userId: Long, postType: PostType): PostData? {
        return postDao.getPost(postId, userId, postType)?.let { labelService.withLabels(it) }
    }
}