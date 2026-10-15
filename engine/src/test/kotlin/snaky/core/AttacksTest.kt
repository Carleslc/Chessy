package snaky.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AttacksTest {

    private fun squares(vararg names: String): Bitboard = names.fold(EMPTY) { bitboard, name -> bitboard or bitboardOf(parseSquare(name)) }

    @Test
    fun knight() {
        assertEquals(squares("b3", "c2"), Attacks.knightAttacks(parseSquare("a1")))
        assertEquals(8, popCount(Attacks.knightAttacks(parseSquare("e4"))))
    }

    @Test
    fun king() {
        assertEquals(squares("a2", "b2", "b1"), Attacks.kingAttacks(parseSquare("a1")))
    }

    @Test
    fun pawn() {
        assertEquals(squares("d5", "f5"), Attacks.pawnAttacks(WHITE, parseSquare("e4")))
        assertEquals(squares("d3", "f3"), Attacks.pawnAttacks(BLACK, parseSquare("e4")))
        assertEquals(squares("b3"), Attacks.pawnAttacks(WHITE, parseSquare("a2")))
    }

    @Test
    fun slidersStopAtFirstBlocker() {
        val occupied = squares("d6", "b4", "d2", "g4", "f5")
        assertEquals(
            squares("d5", "d6", "c4", "b4", "d3", "d2", "e4", "f4", "g4"),
            Attacks.rookAttacks(parseSquare("d4"), occupied)
        )
        assertEquals(
            squares("e5", "f6", "g7", "h8", "c5", "b6", "a7", "c3", "b2", "a1", "e3", "f2", "g1"),
            Attacks.bishopAttacks(parseSquare("d4"), EMPTY)
        )
        assertEquals(squares("e5", "f6"), Attacks.bishopAttacks(parseSquare("d4"), squares("f6")) and squares("e5", "f6", "g7"))
    }

    @Test
    fun emptyBoardMobility() {
        assertEquals(14, popCount(Attacks.rookAttacks(parseSquare("a1"), EMPTY)))
        assertEquals(27, popCount(Attacks.queenAttacks(parseSquare("d4"), EMPTY)))
    }
}
