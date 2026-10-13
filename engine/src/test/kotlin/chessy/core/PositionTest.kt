package chessy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PositionTest {

    private val fens = listOf(
        Position.STARTING_FEN,
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
    )

    @Test
    fun fenRoundTrip() {
        for (fen in fens) {
            assertEquals(fen, Position.fromFen(fen).toFen())
        }
    }

    @Test
    fun optionalFenCounters() {
        assertEquals(Position.STARTING_FEN, Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -").toFen())
    }

    @Test
    fun enPassantOnlyWhenCapturable() {
        // No black pawn can capture on e3
        val position = Position.fromFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertEquals(NO_SQUARE, position.enPassantSquare)
    }

    @Test
    fun invalidFens() {
        val invalid = listOf(
            "",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1", // 7 ranks
            "rnbqkbnr/pppppppp/9/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", // 9 squares
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQ1BNR w kq - 0 1", // no white king
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1", // side to move
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQxq - 0 1", // castling
            "4k2R/8/8/8/8/8/8/4K3 w - - 0 1", // black in check with white to move
        )
        for (fen in invalid) {
            assertFailsWith<IllegalArgumentException>(fen) { Position.fromFen(fen) }
        }
    }

    @Test
    fun keyIsUpdatedIncrementally() {
        for (fen in fens) {
            val position = Position.fromFen(fen)
            val initialKey = position.key
            checkKeys(position, depth = 3)
            assertEquals(initialKey, position.key)
            assertEquals(fen, position.toFen())
        }
    }

    private fun checkKeys(position: Position, depth: Int) {
        if (depth == 0) return
        val moves = MoveGenerator.legalMoves(position)
        for (move in moves.toList()) {
            val keyBefore = position.key
            position.makeMove(move)
            assertEquals(position.computeKey(), position.key, "Key after $move in ${position.toFen()}")
            checkKeys(position, depth - 1)
            position.unmakeMove(move)
            assertEquals(keyBefore, position.key, "Key after unmaking $move")
        }
    }

    @Test
    fun transpositionsHaveSameKey() {
        val a = Position.startingPosition()
        val b = Position.startingPosition()
        play(a, "g1f3", "g8f6", "b1c3")
        play(b, "b1c3", "g8f6", "g1f3")
        assertEquals(a.key, b.key)
        assertEquals(a.toFen().substringBeforeLast(' '), b.toFen().substringBeforeLast(' '))
        play(a, "f6g8")
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun copyKeepsHistory() {
        val position = Position.startingPosition()
        play(position, "g1f3", "g8f6", "f3g1", "f6g8")
        val copy = position.copy()
        assertEquals(position.toFen(), copy.toFen())
        assertEquals(position.key, copy.key)
        assertEquals(1, copy.repetitions())
        // Independent: moves on the copy do not change the original, and the copy can unmake previous moves
        copy.unmakeMove(copy.lastMove())
        assertEquals("rnbqkb1r/pppppppp/5n2/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 3 2", copy.toFen())
        assertEquals(Position.STARTING_FEN.replace(" 0 1", " 4 3"), position.toFen())
    }

    @Test
    fun castlingMovesRook() {
        val position = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        play(position, "e1g1")
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", position.toFen())
        play(position, "e8c8")
        assertEquals("2kr3r/8/8/8/8/8/8/R4RK1 w - - 2 2", position.toFen())
    }

    @Test
    fun castlingRules() {
        // The f1 square is attacked by the rook on f8: no kingside castling
        val position = Position.fromFen("5r1k/7p/8/8/8/8/8/R3K2R w KQ - 0 1")
        val moves = MoveGenerator.legalMoves(position).toList().map { it.toString() }
        assertFalse("e1g1" in moves)
        assertTrue("e1c1" in moves)
    }

    @Test
    fun enPassantAndPromotion() {
        val position = Position.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3")
        play(position, "e5f6")
        assertEquals("rnbqkbnr/ppp1p1pp/5P2/3p4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3", position.toFen())

        val promotion = Position.fromFen("4k3/1P6/8/8/8/8/8/4K3 w - - 0 1")
        val moves = MoveGenerator.legalMoves(promotion).toList().map { it.toString() }
        assertTrue(listOf("b7b8q", "b7b8r", "b7b8b", "b7b8n").all { it in moves })
        play(promotion, "b7b8n")
        assertEquals("1N2k3/8/8/8/8/8/8/4K3 b - - 0 1", promotion.toFen())
    }

    @Test
    fun checkDetection() {
        assertFalse(Position.startingPosition().inCheck())
        assertTrue(Position.fromFen("4k3/8/8/8/8/8/8/4K2r w - - 0 1").inCheck())
        // Checkmate: no legal moves while in check
        val mate = Position.fromFen("R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1")
        assertTrue(mate.inCheck())
        assertTrue(MoveGenerator.legalMoves(mate).isEmpty())
    }

    /** Plays moves in UCI notation, checking that they are legal. */
    private fun play(position: Position, vararg moves: String) {
        for (uci in moves) {
            val move = MoveGenerator.legalMoves(position).toList().find { it.toString() == uci }
                ?: throw AssertionError("Illegal move $uci in ${position.toFen()}")
            position.makeMove(move)
        }
    }
}
