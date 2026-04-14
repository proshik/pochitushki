package ru.proshik.pochitushki.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import java.io.File
import java.util.zip.ZipInputStream
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.proshik.pochitushki.model.PostStoreData
import ru.proshik.pochitushki.model.PostType
import ru.proshik.pochitushki.model.toPostStoreDataWithId
import ru.proshik.pochitushki.repository.PostDao

@Service
class ImportService(
    private val postDao: PostDao,
    private val transactionTemplate: TransactionTemplate
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun importZipArchive(userId: Long, file: File) {
        logger.info("started importZipArchive for userId={}", userId)

        val csvMapper = CsvMapper()
            .registerModule(ParameterNamesModule())
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
        val schema = CsvSchema.emptySchema().withHeader()

        val unreadPosts = mutableListOf<PostStoreData>()
        val archivePosts = mutableListOf<PostStoreData>()

        ZipInputStream(file.inputStream()).use { zipInputStream ->
            var entry = zipInputStream.nextEntry

            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".csv")) {
                    val content = String(zipInputStream.readAllBytes())

                    val postsGroups = csvMapper.readerFor(PocketCsv::class.java).with(schema)
                        .readValues<PocketCsv>(content)
                        .readAll()
                        .partition { csv -> csv.status == "unread" }

                    postsGroups.first.forEach { unreadPosts.add(it.toPostStoreData(userId)) }
                    postsGroups.second.map { archivePosts.add(it.toPostStoreData(userId)) }
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
}
