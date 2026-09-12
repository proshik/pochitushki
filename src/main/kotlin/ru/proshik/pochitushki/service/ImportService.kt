package ru.proshik.pochitushki.service

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.proshik.pochitushki.model.LabelTarget
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.PostStoreDataWithId
import ru.proshik.pochitushki.model.toPostStoreDataWithId
import ru.proshik.pochitushki.repository.PostDao

/** Raised when an uploaded archive exceeds the safety limits (zip bomb / too many records). */
class ImportLimitException(message: String) : RuntimeException(message)

@Service
class ImportService(
    private val postDao: PostDao,
    private val labelService: LabelService,
    private val transactionTemplate: TransactionTemplate
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // Caps to prevent decompression bombs and memory/storage abuse. 16 MB of CSV is well
        // past any real Pocket export (100k rows is a few MB) and comfortably inside a 256 MB
        // heap even with the parsed rows held alongside it.
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 16L * 1024 * 1024 // 16 MB across all CSV entries
        const val MAX_CSV_ENTRIES = 100
        const val MAX_POSTS = 100_000
    }

    fun importZipArchive(userId: Long, file: File) {
        logger.info("started importZipArchive for userId={}", userId)

        val csvMapper = CsvMapper()
            .registerModule(ParameterNamesModule())
        val schema = CsvSchema.emptySchema().withHeader()

        val unreadPosts = mutableListOf<PostStoreData>()
        val archivePosts = mutableListOf<PostStoreData>()

        var totalBytes = 0L
        var csvEntries = 0

        ZipInputStream(file.inputStream()).use { zipInputStream ->
            var entry = zipInputStream.nextEntry

            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".csv")) {
                    if (++csvEntries > MAX_CSV_ENTRIES) {
                        throw ImportLimitException("too many CSV entries (> $MAX_CSV_ENTRIES)")
                    }
                    // Parsed straight off the zip stream through a counting wrapper, instead of
                    // buffering the entry and then copying it into a String: the old way held the
                    // bytes, a copy of them and a UTF-16 String at once — three to four times the
                    // limit, against a 256 MB heap, and OOM takes the whole process down.
                    val counting = BoundedInputStream(zipInputStream, MAX_TOTAL_UNCOMPRESSED_BYTES - totalBytes)
                    val postsGroups = csvMapper.readerFor(PocketCsv::class.java).with(schema)
                        .readValues<PocketCsv>(counting.reader(Charsets.UTF_8))
                        .readAll()
                        .filter { it.hasImportableUrl() }
                        .partition { csv -> csv.status == "unread" }
                    totalBytes += counting.count

                    postsGroups.first.forEach { unreadPosts.add(it.toPostStoreData(userId)) }
                    postsGroups.second.forEach { archivePosts.add(it.toPostStoreData(userId)) }

                    if (unreadPosts.size + archivePosts.size > MAX_POSTS) {
                        throw ImportLimitException("too many posts (> $MAX_POSTS)")
                    }
                }

                entry = zipInputStream.nextEntry
            }
        }

        if (unreadPosts.isEmpty() && archivePosts.isEmpty()){
            logger.info("zip archive is empty, not found items: userId={}", userId)
            return
        }

        logger.debug("success read zip archive: userId={}", userId)

        val unreadPostIds = postDao.getPostSequenceIds(1, unreadPosts.size, PostType.UNREAD)
        val archivePostIds = postDao.getPostSequenceIds(1, archivePosts.size, PostType.ARCHIVE)

        val unreadIdsIterator = unreadPostIds.iterator()
        val archiveIdsIterator = archivePostIds.iterator()

        val toStoreUnreadPosts = unreadPosts
            .map { it.toPostStoreDataWithId(unreadIdsIterator.next()) }

        val toStoreArchivePosts = archivePosts
            .map { it.toPostStoreDataWithId(archiveIdsIterator.next()) }

        transactionTemplate.executeWithoutResult {
            // tags are dropped on the floor here on purpose: the CSV's tag column now
            // becomes real labels (see migration 6). The legacy tags column still exists
            // and still holds whatever earlier imports put there, but nothing writes it.
            postDao.addPosts(toStoreUnreadPosts.map { it.copy(tags = null) }, PostType.UNREAD)
            postDao.addPosts(toStoreArchivePosts.map { it.copy(tags = null) }, PostType.ARCHIVE)

            attachLabels(userId, toStoreUnreadPosts, LabelTarget.UNREAD)
            attachLabels(userId, toStoreArchivePosts, LabelTarget.ARCHIVE)
        }

        logger.info("success store posts in DB: userId={}, unread posts={}, archive posts={}", userId, unreadPosts.size, archivePosts.size)
    }

    /**
     * Turns the CSV tag column into labels. createLabel is an upsert, so a tag repeated
     * across a thousand rows still resolves to one row - but resolve each distinct name
     * once rather than once per post.
     */
    private fun attachLabels(userId: Long, posts: List<PostStoreDataWithId>, target: LabelTarget) {
        val withTags = posts.filter { !it.tags.isNullOrEmpty() }
        if (withTags.isEmpty()) return

        val labelIdByName = withTags
            .flatMap { it.tags.orEmpty() }
            .mapNotNull { name -> name.trim().takeIf { it.isNotEmpty() } }
            .distinct()
            .mapNotNull { name ->
                try {
                    name to labelService.createLabel(userId, name).id
                } catch (e: InvalidLabelNameException) {
                    logger.debug("skipping unusable imported tag '{}': {}", name, e.message)
                    null
                }
            }
            .toMap()

        for (post in withTags) {
            post.tags.orEmpty()
                .mapNotNull { labelIdByName[it.trim()] }
                .distinct()
                .forEach { labelId -> labelService.attachLabel(post.id, labelId, userId, target) }
        }
    }

    /**
     * Passes the zip entry through while counting it, and fails the import as soon as the
     * decompressed stream exceeds [limit] — a small archive can otherwise expand to gigabytes
     * (zip bomb). The stream is not closed on purpose: it is one entry of a ZipInputStream
     * that the caller keeps reading.
     */
    private class BoundedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {

        var count: Long = 0
            private set

        init {
            if (limit <= 0) throw ImportLimitException("uncompressed size exceeds limit")
        }

        override fun read(): Int {
            val b = delegate.read()
            if (b != -1) countBytes(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) countBytes(read.toLong())
            return read
        }

        override fun close() {
            // The underlying ZipInputStream outlives this wrapper.
        }

        private fun countBytes(read: Long) {
            count += read
            if (count > limit) throw ImportLimitException("uncompressed size exceeds limit")
        }
    }
}
