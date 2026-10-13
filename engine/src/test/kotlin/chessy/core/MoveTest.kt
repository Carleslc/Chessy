package chessy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveTest {

    @Test
    fun encoding() {
        val move = Move(parseSquare("e7"), parseSquare("d8"), Move.promotionFlags(KNIGHT, capture = true))
        assertEquals(parseSquare("e7"), move.from)
        assertEquals(parseSquare("d8"), move.to)
        assertTrue(move.isCapture)
        assertTrue(move.isPromotion)
        assertEquals(KNIGHT, move.promotionType)
        assertFalse(move.isCastling)
        assertEquals("e7d8n", move.toString())
    }

    @Test
    fun flags() {
        assertTrue(Move(E1, G1, Move.KING_CASTLE).isCastling)
        assertTrue(Move(parseSquare("e5"), parseSquare("d6"), Move.EN_PASSANT).let { it.isEnPassant && it.isCapture })
        assertTrue(Move(parseSquare("e2"), parseSquare("e4"), Move.DOUBLE_PAWN_PUSH).isDoublePawnPush)
        assertEquals("e2e4", Move(parseSquare("e2"), parseSquare("e4")).toString())
        assertEquals("0000", Move.NONE.toString())
    }
}
