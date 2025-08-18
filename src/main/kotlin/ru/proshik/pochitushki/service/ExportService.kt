package ru.proshik.pochitushki.service

import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.UUID
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.repository.PostDao


@Service
class ExportService(
    private val postDao: PostDao
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        val HEADERS = arrayOf("title", "url", "time_added", "tags", "status")
    }

    fun export(userId: Long): String? {
        val archivePosts = postDao.getPosts(userId = userId, postType = PostType.ARCHIVE, offset = 0)
        val unreadPosts = postDao.getPosts(userId = userId, postType = PostType.UNREAD, offset = 0)

        val posts = archivePosts + unreadPosts

        val csvFormat = CSVFormat.DEFAULT.builder()
            .setHeader(*HEADERS)
            .setDelimiter(",")
            .get()

//        var i: Int = 0

        val fileUuid = UUID.randomUUID().toString()

        val file = File("/tmp/export_$fileUuid.csv")

        val fileWriter = FileWriter(file)

        try {
            posts.chunked(10000)

            CSVPrinter(fileWriter, csvFormat).use { csvPrinter ->
                for (post in archivePosts) {
                    val tags = post.tags?.joinToString(separator = "|") ?: ""
                    csvPrinter.printRecord(post.title, post.url, post.createdDate, tags, "archive")
                }

                for (post in unreadPosts) {
                    val tags = post.tags?.joinToString(separator = "|") ?: ""
                    csvPrinter.printRecord(post.title, post.url, post.createdDate, tags, "unread")
                }
            }
        } catch (e: IOException) {
            logger.warn("Exception while exporting posts: userId={}", userId, e)

            return null
        }




        return fileUuid
    }
}