package ru.proshik.pochitushki.service.telegram.card

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardRefTest {

    @Test
    fun `round trip for every list and loc`() {
        for (list in CardList.entries) for (loc in PostLoc.entries) for (widget in listOf(false, true)) {
            val ref = CardRef(list, 17, 4242L, loc, widget)
            assertEquals(ref, CardRef.decode(ref.encode()), "round trip of $ref")
        }
    }

    @Test
    fun `widget flag is the upper-case list code`() {
        assertEquals("u:3:123:u", CardRef(CardList.UNREAD, 3, 123, PostLoc.UNREAD).encode())
        assertEquals("U:3:123:u", CardRef(CardList.UNREAD, 3, 123, PostLoc.UNREAD, widget = true).encode())
    }

    @Test
    fun `worst case fits 64 bytes`() {
        val ref = CardRef(CardList.FAVORITES, 999_999, Long.MAX_VALUE, PostLoc.ARCHIVE, widget = true)
        val toggle = cardCallback(CardAction.LABEL_TOGGLE, ref, Long.MAX_VALUE.toString())
        val engine = cardCallback(CardAction.PDF_ENGINE, ref, "playwright")
        assertTrue(toggle.toByteArray().size <= 64, "label toggle is ${toggle.toByteArray().size} bytes")
        assertTrue(engine.toByteArray().size <= 64, "pdf engine is ${engine.toByteArray().size} bytes")
    }

    @Test
    fun `decode rejects garbage`() {
        for (bad in listOf("", "x:1:2:u", "u:-1:2:u", "u:1:abc:u", "u:1:2", "u:1:2:z", "uu:1:2:u", "u:1:-2:u")) {
            assertNull(CardRef.decode(bad), "expected null for '$bad'")
        }
    }

    @Test
    fun `extra survives`() {
        assertEquals(
            CardRef(CardList.UNREAD, 0, 5, PostLoc.UNREAD) to "42",
            parseCardCallback("u:0:5:u:42"),
        )
        assertEquals(CardRef(CardList.UNREAD, 0, 5, PostLoc.UNREAD) to null, parseCardCallback("u:0:5:u"))
        assertNull(parseCardCallback("zz"))
    }

    @Test
    fun `callback is action, bar and ref`() {
        val ref = CardRef(CardList.ARCHIVE, 2, 7, PostLoc.ARCHIVE)
        assertEquals("cu|a:2:7:a", cardCallback(CardAction.UNREAD, ref))
        assertEquals("cm|a:2:7:a:1", cardCallback(CardAction.MORE, ref, "1"))
    }
}
