package ru.proshik.pochitushki.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.LabelData
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.repository.LabelDao

class InvalidLabelNameException(message: String) : RuntimeException(message)

@Service
class LabelService(private val labelDao: LabelDao) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun getLabels(userId: Long): List<LabelData> = labelDao.getLabels(userId)

    fun findLabel(labelId: Long, userId: Long): LabelData? = labelDao.findLabel(labelId, userId)

    /**
     * Names are normalised before they reach the unique index, so "Kotlin", "kotlin "
     * and "#kotlin" are one label rather than three that render identically.
     */
    fun createLabel(userId: Long, rawName: String): LabelData {
        val name = normalise(rawName)
        logger.debug("User {} creating label '{}'", userId, name)
        return labelDao.createLabel(userId, name)
    }

    fun deleteLabel(labelId: Long, userId: Long): Boolean = labelDao.deleteLabel(labelId, userId) > 0

    fun attachLabel(postId: Long, labelId: Long, userId: Long, target: LabelTarget): Boolean =
        labelDao.attachLabel(postId, labelId, userId, target)

    fun detachLabel(postId: Long, labelId: Long, userId: Long, target: LabelTarget): Boolean =
        labelDao.detachLabel(postId, labelId, userId, target)

    /** Carries label links onto the row an archive/unread move just created. */
    fun copyLabelsOnMove(fromPostId: Long, toPostId: Long, from: LabelTarget, to: LabelTarget) {
        labelDao.copyLabels(fromPostId, toPostId, from, to)
    }

    /** Returns the posts with their labels attached, in two queries regardless of page size. */
    fun withLabels(posts: List<PostData>): List<PostData> {
        if (posts.isEmpty()) return posts

        val unreadIds = posts.filterNot { it.isArchived }.map { it.id }
        val archiveIds = posts.filter { it.isArchived }.map { it.id }
        val byPost = labelDao.findLabelsForPosts(unreadIds, archiveIds)
        if (byPost.isEmpty()) return posts

        return posts.map { post ->
            val labels = byPost[LabelTarget.of(post.isArchived) to post.id]
            if (labels == null) post else post.copy(labels = labels)
        }
    }

    fun withLabels(post: PostData): PostData = withLabels(listOf(post)).first()

    private fun normalise(rawName: String): String {
        val name = rawName.trim().removePrefix("#").trim().replace(WHITESPACE, " ")
        if (name.isEmpty()) throw InvalidLabelNameException("Label name must not be blank")
        if (name.length > MAX_NAME_LENGTH) {
            throw InvalidLabelNameException("Label name must be at most $MAX_NAME_LENGTH characters")
        }
        return name
    }

    companion object {
        const val MAX_NAME_LENGTH = 40

        private val WHITESPACE = Regex("\\s+")
    }
}
