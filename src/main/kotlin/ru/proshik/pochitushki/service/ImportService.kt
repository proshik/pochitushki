package ru.proshik.pochitushki.service

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.toPostStoreDataWithId
import ru.proshik.pochitushki.repository.PostDao

/** Raised when an uploaded archive exceeds the safety limits (zip bomb / too many records). */
class ImportLimitException(message: String) : RuntimeException(message)

@Service
class ImportService(
    private val postDao: PostDao,
    private val transactionTemplate: TransactionTemplate
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // Caps to prevent decompression bombs and memory/storage abuse.
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024 * 1024 // 64 MB across all CSV entries
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
                    val bytes = readBounded(zipInputStream, MAX_TOTAL_UNCOMPRESSED_BYTES - totalBytes)
                    totalBytes += bytes.size
                    val content = String(bytes, Charsets.UTF_8)

                    val postsGroups = csvMapper.readerFor(PocketCsv::class.java).with(schema)
                        .readValues<PocketCsv>(content)
                        .readAll()
                        .filter { !it.url.isNullOrBlank() }
                        .partition { csv -> csv.status == "unread" }

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
            postDao.addPosts(toStoreUnreadPosts, PostType.UNREAD)
            postDao.addPosts(toStoreArchivePosts, PostType.ARCHIVE)
        }

        logger.info("success store posts in DB: userId={}, unread posts={}, archive posts={}", userId, unreadPosts.size, archivePosts.size)
    }

    /**
     * Reads the current zip entry fully but refuses to buffer more than [limit] bytes — a small
     * compressed archive can otherwise expand to gigabytes (zip bomb) and exhaust the heap.
     */
    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        if (limit <= 0) throw ImportLimitException("uncompressed size exceeds limit")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw ImportLimitException("uncompressed size exceeds limit")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
