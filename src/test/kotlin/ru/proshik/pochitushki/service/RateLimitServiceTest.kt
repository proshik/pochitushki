package ru.proshik.pochitushki.service

import io.github.bucket4j.TimeMeter
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.proshik.pochitushki.service.RateLimitService.Action

class RateLimitServiceTest {

    /** Часы, которые двигаются только тогда, когда тест так решил. */
    private class ManualClock : TimeMeter {
        var nanos = 0L
        override fun currentTimeNanos(): Long = nanos
        override fun isWallClockBased(): Boolean = false
        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }
    }

    private val clock = ManualClock()
    private val service = RateLimitService(clock)

    private fun acquired(userId: Long, action: Action, attempts: Int): Int =
        (1..attempts).count { service.tryAcquire(userId, action) }

    @Test
    fun `allows the per-minute limit as a burst, then refuses`() {
        assertEquals(Action.ADD_POST.limitPerMinute, acquired(1, Action.ADD_POST, 100))
        assertFalse(service.tryAcquire(1, Action.ADD_POST))
    }

    @Test
    fun `tokens come back gradually, not all at once at a minute boundary`() {
        // Ровно то, что ломалось у фиксированного окна: норма в конце минуты и ещё одна
        // норма в начале следующей. У token bucket после опустошения за секунду набегает
        // один токен при 60 в минуту, а не 60.
        acquired(1, Action.ADD_POST, Action.ADD_POST.limitPerMinute)

        clock.advance(Duration.ofSeconds(1))

        assertEquals(1, acquired(1, Action.ADD_POST, 10))
    }

    @Test
    fun `a full refill period restores the whole budget`() {
        acquired(1, Action.IMPORT, Action.IMPORT.limitPerMinute)
        assertFalse(service.tryAcquire(1, Action.IMPORT))

        clock.advance(RateLimitService.REFILL_PERIOD)

        assertEquals(Action.IMPORT.limitPerMinute, acquired(1, Action.IMPORT, 10))
    }

    @Test
    fun `one user spending the budget does not affect another`() {
        acquired(1, Action.GENERATE_PDF, Action.GENERATE_PDF.limitPerMinute)

        assertFalse(service.tryAcquire(1, Action.GENERATE_PDF))
        assertTrue(service.tryAcquire(2, Action.GENERATE_PDF))
    }

    @Test
    fun `each action has its own budget`() {
        acquired(1, Action.IMPORT, Action.IMPORT.limitPerMinute)

        assertFalse(service.tryAcquire(1, Action.IMPORT))
        assertTrue(service.tryAcquire(1, Action.ADD_POST))
    }
}
