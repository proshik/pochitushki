package ru.proshik.pochitushki.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Per-user budget for the operations that cost real resources: each saved link is an outbound
 * fetch of a URL the user chose, and each PDF is a fetch plus a render. Without a cap one
 * account can point the service at someone else's host as a traffic amplifier, or keep every
 * request thread busy.
 *
 * A Bucket4j token bucket per (user, action): capacity equals the per-minute limit and tokens
 * refill greedily, spread evenly over the minute. So an idle user may burst up to the limit,
 * but cannot get twice the limit by straddling a minute boundary — which a fixed window allowed.
 *
 * Buckets live in memory, so the budget is per replica — fine while the bot pins the service to
 * one instance anyway. Not a substitute for a limiter at the proxy, which also sees anonymous
 * traffic; this one exists because the expensive work is authenticated.
 */
@Service
class RateLimitService internal constructor(private val timeMeter: TimeMeter) {

    constructor() : this(TimeMeter.SYSTEM_MILLISECONDS)

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * An untouched bucket is full again after one refill period, so dropping it after a longer
     * idle stretch loses nothing. The size cap only matters under a flood of distinct users;
     * evicting an active bucket then resets that user's budget, which errs on the lenient side.
     */
    private val buckets: Cache<String, Bucket> = Caffeine.newBuilder()
        .expireAfterAccess(IDLE_EVICTION)
        .maximumSize(100_000)
        .build()

    /** True when the call fits in the budget; false when the user has spent it for now. */
    fun tryAcquire(userId: Long, action: Action): Boolean {
        val bucket = buckets.get("$userId:${action.name}") { newBucket(action) }!!
        if (!bucket.tryConsume(1)) {
            logger.info("Rate limit hit: userId={}, action={}", userId, action)
            return false
        }
        return true
    }

    private fun newBucket(action: Action): Bucket =
        Bucket.builder()
            .withCustomTimePrecision(timeMeter)
            .addLimit { limit ->
                limit.capacity(action.limitPerMinute.toLong())
                    .refillGreedy(action.limitPerMinute.toLong(), REFILL_PERIOD)
            }
            .build()

    enum class Action(val limitPerMinute: Int) {
        /** Saving a link: one outbound fetch of a user-supplied URL each. */
        ADD_POST(60),

        /** Generating a PDF: a fetch, image downloads and a render. */
        GENERATE_PDF(20),

        /** Importing an archive: parses up to MAX_POSTS rows and writes them in one transaction. */
        IMPORT(3),
    }

    companion object {
        val REFILL_PERIOD: Duration = Duration.ofMinutes(1)
        private val IDLE_EVICTION: Duration = Duration.ofMinutes(10)
    }
}
