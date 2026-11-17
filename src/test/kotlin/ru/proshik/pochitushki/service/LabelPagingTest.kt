package ru.proshik.pochitushki.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LabelPagingTest {

    @Test
    fun `pages of 50, at least one`() {
        assertEquals(1, LabelPaging.pages(0))
        assertEquals(1, LabelPaging.pages(50))
        assertEquals(2, LabelPaging.pages(51))
        assertEquals(4, LabelPaging.pages(158))
    }

    @Test
    fun `page out of range goes to the last page`() {
        assertEquals(4, LabelPaging.resolve("5", total = 158))
        assertEquals(4, LabelPaging.resolve("999999999999", total = 158))
        assertEquals(1, LabelPaging.resolve("3", total = 0))
    }

    @Test
    fun `garbage or below one goes to the first page`() {
        for (raw in listOf(null, "", "abc", "2.5", "0", "-3", "1e3")) {
            assertEquals(1, LabelPaging.resolve(raw, total = 158), "page=$raw")
        }
        assertEquals(2, LabelPaging.resolve(" 2 ", total = 158))
    }

    @Test
    fun `page of the n-th label`() {
        assertEquals(1, LabelPaging.pageOf(position = 0))
        assertEquals(1, LabelPaging.pageOf(position = 49))
        assertEquals(2, LabelPaging.pageOf(position = 50))
    }

    @Test
    fun `pager items show every page when there are few`() {
        assertEquals(listOf(1, 2, 3, 4), LabelPaging.items(page = 2, pages = 4))
        assertEquals(emptyList<Int?>(), LabelPaging.items(page = 1, pages = 1))
    }

    @Test
    fun `pager items fold the middle of a long range`() {
        // null is a gap ("…").
        assertEquals(listOf(1, null, 9, 10, 11, null, 20), LabelPaging.items(page = 10, pages = 20))
        assertEquals(listOf(1, 2, 3, null, 20), LabelPaging.items(page = 2, pages = 20))
        assertEquals(listOf(1, null, 18, 19, 20), LabelPaging.items(page = 20, pages = 20))
    }
}
