package ru.proshik.pochitushki.service.telegram

import jakarta.annotation.PreDestroy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Where the bot's slow work runs.
 *
 * kotlin-telegram-bot dispatches every update on a single thread, one after another, so a PDF
 * render (seconds to a minute) or an import blocks every other user's commands behind it. In
 * webhook mode it is worse: the HTTP call from Telegram is still waiting on that same queue, so
 * Telegram times out and redelivers the update, and the work runs twice.
 *
 * Handing those three operations to this small pool keeps the dispatcher free. The queue is
 * bounded and rejection is visible: a user who is told "try again later" is better off than one
 * whose request sits in an unbounded queue for ten minutes.
 */
@Component
class BotTaskExecutor {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val threadCounter = AtomicInteger()

    private val executor = ThreadPoolExecutor(
        2, 4,
        60L, TimeUnit.SECONDS,
        ArrayBlockingQueue(32),
    ) { runnable ->
        Thread(runnable, "bot-task-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    /**
     * Runs [task] off the dispatcher thread. Returns false when the pool is saturated, and the
     * caller is expected to tell the user rather than drop the request silently.
     */
    fun submit(description: String, task: () -> Unit): Boolean = try {
        executor.execute {
            try {
                task()
            } catch (e: Exception) {
                // Nothing above this catches: an escaping exception would kill the pool thread
                // and (in the library's own dispatcher) print a raw stack trace to stderr.
                logger.warn("bot task failed: {}", description, e)
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        logger.warn("bot task rejected, pool saturated: {}", description)
        false
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
            logger.warn("bot task executor did not drain in time, forcing shutdown")
            executor.shutdownNow()
        }
    }
}
