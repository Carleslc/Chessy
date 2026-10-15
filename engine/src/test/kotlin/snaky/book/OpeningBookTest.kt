package snaky.book

import snaky.core.Notation
import snaky.core.Position
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpeningBookTest {

    private fun position(vararg moves: String): Position =
        Position.startingPosition().apply { for (move in moves) makeMove(Notation.parseUci(this, move)) }

    @Test
    fun polyglotKeys() {
        // Test values of the Polyglot specification
        assertEquals(0x463b96181691fc9cU.toLong(), OpeningBook.key(position()))
        assertEquals(0x823c9b50fd114196U.toLong(), OpeningBook.key(position("e2e4")))
        assertEquals(0x0756b94461c50fb0U.toLong(), OpeningBook.key(position("e2e4", "d7d5")))
        assertEquals(0x662fafb965db29d4U.toLong(), OpeningBook.key(position("e2e4", "d7d5", "e4e5")))
        assertEquals(0x22a48b5a8e47ff78U.toLong(), OpeningBook.key(position("e2e4", "d7d5", "e4e5", "f7f5")))
        assertEquals(0x652a607ca3f242c1U.toLong(), OpeningBook.key(position("e2e4", "d7d5", "e4e5", "f7f5", "e1e2")))
        assertEquals(0x00fdd303c946bdd9U.toLong(), OpeningBook.key(position("e2e4", "d7d5", "e4e5", "f7f5", "e1e2", "e8f7")))
        assertEquals(0x3c8123ea7b067637U.toLong(), OpeningBook.key(position("a2a4", "b7b5", "h2h4", "b5b4", "c2c4")))
        assertEquals(0x5c3f9b829b279560U.toLong(), OpeningBook.key(position("a2a4", "b7b5", "h2h4", "b5b4", "c2c4", "b4c3", "a1a3")))
    }

    @Test
    fun bookOfSnaky() {
        val book = OpeningBook.resource("book")
        val first = book.moves(position()).map { it.first.toString() }.toSet()
        assertTrue(first.containsAll(listOf("d2d4", "e2e4")), first.toString())
        // Against 1.d4 Snaky prefers the King's Indian (1...Nf6) and against 1.e4 the French (1...e6)
        assertEquals("g8f6", book.moves(position("d2d4")).maxBy { it.second }.first.toString())
        assertEquals("e7e6", book.moves(position("e2e4")).maxBy { it.second }.first.toString())
        assertNull(book.pick(position("a2a3")))
        // Castling is in the book as the king taking its rook (O-O in the closed Ruy Lopez)
        val ruy = position("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6")
        assertEquals("e1g1", book.moves(ruy).maxBy { it.second }.first.toString())
    }

    @Test
    fun oneOpening() {
        assertTrue("French" in OpeningBook.openings && "KingsIndian" in OpeningBook.openings)
        val french = OpeningBook.resource("French")
        assertEquals(setOf("e2e4"), french.moves(position()).map { it.first.toString() }.toSet())
        assertEquals("e7e6", french.pick(position("e2e4"), Random(1)).toString())
        assertNotNull(french.pick(position("e2e4", "e7e6")))
    }
}
