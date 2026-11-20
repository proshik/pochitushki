package ru.proshik.pochitushki.service

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluralsTest {

    private val ru = Locale("ru")
    private val en = Locale("en")

    @Test
    fun `russian has one, few and many with 11-14 as many`() {
        val expected = mapOf(
            1L to "one", 21L to "one", 101L to "one",
            2L to "few", 3L to "few", 4L to "few", 22L to "few", 34L to "few",
            0L to "many", 5L to "many", 10L to "many", 11L to "many", 12L to "many",
            13L to "many", 14L to "many", 20L to "many", 25L to "many", 111L to "many", 112L to "many",
        )
        expected.forEach { (n, form) -> assertEquals(form, Plurals.category(n, ru), "n=$n") }
    }

    @Test
    fun `english has one and other`() {
        assertEquals("one", Plurals.category(1, en))
        listOf(0L, 2L, 11L, 21L).forEach { assertEquals("other", Plurals.category(it, en), "n=$it") }
    }
}
