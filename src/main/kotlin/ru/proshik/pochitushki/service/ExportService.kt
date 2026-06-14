package ru.proshik.pochitushki.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.PostData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao

@Service
class ExportService(
    private val postDao: PostDao
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val HEADERS = arrayOf("title", "url", "time_added", "tags", "status", "is_favorite")
        const val CHUNK_SIZE = 1000
        private val CSV_FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
    }

    fun export(userId: Long): File? {
        val unreadPosts = postDao.getPosts(userId = userId, postType = PostType.UNREAD, limit = Int.MAX_VALUE, offset = 0)
        val archivePosts = postDao.getPosts(userId = userId, postType = PostType.ARCHIVE, limit = Int.MAX_VALUE, offset = 0)

        val allPosts: List<Pair<PostData, String>> =
            unreadPosts.map { it to "unread" } + archivePosts.map { it to "archive" }

        if (allPosts.isEmpty()) {
            logger.info("export: no posts found for userId={}", userId)
            return null
        }

        val file = File.createTempFile("pochitushki_export_${userId}_", ".zip")

        try {
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                allPosts.chunked(CHUNK_SIZE).forEachIndexed { index, chunk ->
                    val csvBytes = buildCsvChunk(chunk)
                    val entryName = "posts_%03d.csv".format(index + 1)
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(csvBytes)
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            logger.warn("export: failed to generate ZIP for userId={}", userId, e)
            if (file.exists()) file.delete()
            return null
        }

        logger.info("export: ZIP created for userId={}, posts={}", userId, allPosts.size)

        return file
    }

    private fun buildCsvChunk(chunk: List<Pair<PostData, String>>): ByteArray {
        val csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader(*HEADERS)
            .get()

        val out = ByteArrayOutputStream()
        val writer = out.writer(Charsets.UTF_8)
        CSVPrinter(writer, csvFormat).use { printer ->
            for ((post, status) in chunk) {
                val timeAdded = post.createdDate
                    .atZone(TimeZone.getDefault().toZoneId())
                    .toEpochSecond()
                val tags = post.tags?.joinToString(",") ?: ""
                printer.printRecord(
                    sanitizeForCsv(post.title), sanitizeForCsv(post.url), timeAdded,
                    sanitizeForCsv(tags), status, post.isFavorite
                )
            }
        }

        return out.toByteArray()
    }

    /**
     * Neutralize CSV formula injection: spreadsheet apps execute a cell that starts with
     * = + - @ (or a leading tab/CR). Prefix such user-controlled values with a single quote.
     */
    private fun sanitizeForCsv(value: String?): String {
        val v = value ?: ""
        return if (v.isNotEmpty() && v.first() in CSV_FORMULA_TRIGGERS) "'$v" else v
    }
}
