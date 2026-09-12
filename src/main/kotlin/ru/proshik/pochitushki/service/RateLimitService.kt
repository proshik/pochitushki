package ru.proshik.pochitushki.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Per-user budget for the operations that cost real resources: each saved link is an outbound
 * fetch of a URL the user chose, and each PDF is a fetch plus a render. Without a cap one
 * account can point the service at someone else's host as a traffic amplifier, or keep every
 * request thread busy.
 *
 * A fixed window per (user, action) held in memory. In-memory means the budget is per replica —
 * fine while the bot pins the service to one instance anyway, and the DB has no business
 * counting clicks. Not a substitute for a limiter at the proxy, which also sees anonymous
 * traffic; this one exists because the expensive work is authenticated.
 */
@Service
class RateLimitService {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val counters: Cache<String, AtomicInteger> = Caffeine.newBuilder()
        .expireAfterWrite(WINDOW)
        .maximumSize(100_000)
        .build()

    /** True when the call fits in the budget; false when the user has spent it for this window. */
    fun tryAcquire(userId: Long, action: Action): Boolean {
        val key = "$userId:${action.name}"
        val used = counters.get(key) { AtomicInteger(0) }!!.incrementAndGet()
        if (used > action.limitPerWindow) {
            logger.info("Rate limit hit: userId={}, action={}, used={}", userId, action, used)
            return false
        }
        return true
    }

    enum class Action(val limitPerWindow: Int) {
        /** Saving a link: one outbound fetch of a user-supplied URL each. */
        ADD_POST(60),

        /** Generating a PDF: a fetch, image downloads and a render. */
        GENERATE_PDF(20),

        /** Importing an archive: parses up to MAX_POSTS rows and writes them in one transaction. */
        IMPORT(3),
    }

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(1)
    }
}
