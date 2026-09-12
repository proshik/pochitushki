package ru.proshik.pochitushki.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.proshik.pochitushki.repository.PdfCacheDao

/**
 * Remembers generated PDFs so asking for the same article twice costs one fetch and
 * one render, not two.
 *
 * Entries expire after [TTL_DAYS]: an article the site has since rewritten should not
 * be handed out from the cache forever, but a reader coming back to the same link
 * within the month gets it instantly.
 */
@Service
class PdfCacheService(private val pdfCacheDao: PdfCacheDao) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the cached render, or produces one with [generate] and stores it.
     * A failure in [generate] propagates and nothing is cached.
     */
    fun getOrGenerate(userId: Long, url: String, engine: String, generate: () -> ByteArray): ByteArray {
        pdfCacheDao.find(userId, url, engine, TTL_DAYS)?.let { cached ->
            logger.debug("pdf cache hit: userId={}, engine={}, url={}", userId, engine, url)
            return cached
        }

        val bytes = generate()
        // A render larger than this is handed to the reader but not kept: the column is a
        // bytea read whole into the heap on every hit, and one 200 MB row would be a cache
        // entry nobody can afford to serve.
        if (bytes.size <= MAX_CACHED_BYTES) {
            pdfCacheDao.store(userId, url, engine, bytes)
            // Cheap, bounded housekeeping on a path that already did the expensive thing.
            pdfCacheDao.deleteExpired(userId, TTL_DAYS)
            logger.debug("pdf cached: userId={}, engine={}, bytes={}", userId, engine, bytes.size)
        } else {
            logger.info("pdf too large to cache: userId={}, engine={}, bytes={}", userId, engine, bytes.size)
        }
        return bytes
    }

    /**
     * Sweeps expired rows for everyone, not just the user who happened to write last.
     * Without it the rows of a user who never asks for another PDF stay forever — in the
     * table and in every backup.
     */
    @Scheduled(cron = "\${pdf.cache.cleanup-cron:0 30 3 * * *}")
    fun cleanUpExpired() {
        val deleted = pdfCacheDao.deleteExpired(TTL_DAYS)
        if (deleted > 0) logger.info("pdf cache cleanup removed {} expired rows", deleted)
    }

    companion object {
        const val TTL_DAYS = 30L
        const val MAX_CACHED_BYTES = 20 * 1024 * 1024
    }
}
