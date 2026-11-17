package ru.proshik.pochitushki.service.telegram

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component
import ru.proshik.pochitushki.service.telegram.card.CardRef

/**
 * Remembers that a chat was asked to type a label name, so the next plain message is
 * read as that name instead of as a URL to save.
 *
 * Deliberately in memory: a restart drops the prompt and the user's next message goes
 * back to being a link, which is the behaviour they would get by ignoring the prompt
 * anyway. A table for a state that lives ten seconds would be worse than the problem.
 */
@Component
class PendingLabelInput {

    /** [ref] is the card the prompt came from: which post gets the label. */
    data class Pending(
        val ref: CardRef,
        val askedAt: Instant,
    )

    private val pending = ConcurrentHashMap<Long, Pending>()

    fun await(chatId: Long, ref: CardRef) {
        pending[chatId] = Pending(ref, Instant.now())
    }

    /** Returns and clears the prompt, or null if there is none or it went stale. */
    fun take(chatId: Long): Pending? {
        val found = pending.remove(chatId) ?: return null
        return found.takeIf { Duration.between(it.askedAt, Instant.now()) < TTL }
    }

    fun cancel(chatId: Long) {
        pending.remove(chatId)
    }

    companion object {
        private val TTL: Duration = Duration.ofMinutes(5)
    }
}
