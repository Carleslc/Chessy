package snaky.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticExchangeTest {

    private fun see(fen: String, uci: String): Int {
        val position = Position.fromFen(fen)
        return StaticExchange.evaluate(position, Notation.parseUci(position, uci))
    }

    @Test
    fun freeCapture() {
        assertEquals(100, see("1k1r4/1pp4p/p7/4p3/8/P5P1/1PP4P/2K1R3 w - - 0 1", "e1e5"))
    }

    @Test
    fun defendedPawnCapturedByQueen() {
        // Qxd5 exd5: wins a pawn, loses the queen
        assertEquals(100 - 900, see("4k3/8/4p3/3p4/8/8/8/3QK3 w - - 0 1", "d1d5"))
    }

    @Test
    fun exchangeWithXrays() {
        // Example of the chessprogramming wiki: Nxe5 Nxe5 and white should not continue (the bishop f6 and queen h8 defend e5)
        assertEquals(-220, see("1k1r3q/1ppn3p/p4b2/4p3/8/P2N2P1/1PP1R1BP/2K1Q3 w - - 0 1", "d3e5"))
    }

    @Test
    fun equalTrade() {
        // Rxd5 Rxd5: rook for rook. Without the defender, a free rook
        assertEquals(0, see("3r2k1/8/8/3r4/8/8/8/3R2K1 w - - 0 1", "d1d5"))
        assertEquals(500, see("6k1/8/8/3r4/8/8/8/3R2K1 w - - 0 1", "d1d5"))
    }

    @Test
    fun quietMoveToAttackedSquare() {
        // Nf3-g5 attacked by the pawn h6: loses the knight
        assertEquals(-320, see("4k3/8/7p/8/8/5N2/8/4K3 w - - 0 1", "f3g5"))
        assertEquals(0, see(Position.STARTING_FEN, "g1f3"))
    }

    @Test
    fun enPassant() {
        assertEquals(100, see("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6"))
    }

    /**
     * Reference SEE playing the captures: each side captures on the square with its least valuable piece,
     * or stops if capturing does not pay. Pins and checks are ignored, like the real SEE.
     */
    private fun referenceSee(position: Position, move: Move): Int {
        val captured = if (move.isEnPassant) StaticExchange.VALUES[PAWN] else position.pieceOn(move.to).let { if (it == NO_PIECE) 0 else StaticExchange.VALUES[typeOf(it)] }
        val promotion = if (move.isPromotion) StaticExchange.VALUES[move.promotionType] - StaticExchange.VALUES[PAWN] else 0
        position.makeMove(move)
        val score = captured + promotion - bestRecapture(position, move.to)
        position.unmakeMove(move)
        return score
    }

    private fun bestRecapture(position: Position, square: Square): Int {
        val moves = MoveList()
        MoveGenerator.generatePseudoLegal(position, moves, tacticalOnly = true)
        val recaptures = moves.toList().filter { it.to == square && !it.isEnPassant && (!it.isPromotion || it.promotionType == QUEEN) }
        // Least valuable attacker first
        val recapture = recaptures.minByOrNull { typeOf(position.pieceOn(it.from)) } ?: return 0
        val value = StaticExchange.VALUES[typeOf(position.pieceOn(square))]
        val promotion = if (recapture.isPromotion) StaticExchange.VALUES[QUEEN] - StaticExchange.VALUES[PAWN] else 0
        position.makeMove(recapture)
        val score = value + promotion - bestRecapture(position, square)
        position.unmakeMove(recapture)
        return maxOf(0, score)
    }

    @Test
    fun matchesReferenceOnManyPositions() {
        val fens = listOf(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "1k1r3q/1ppn3p/p4b2/4p3/8/P2N2P1/1PP1R1BP/2K1Q3 w - - 0 1",
        )
        var checked = 0
        for (fen in fens) {
            val position = Position.fromFen(fen)
            // Captures of the position and of the positions after each legal move
            val positions = listOf(position) + MoveGenerator.legalMoves(position).toList().map { move -> position.copy().also { it.makeMove(move) } }
            for (current in positions) {
                for (move in MoveGenerator.legalMoves(current).toList().filter { it.isCapture && (!it.isPromotion || it.promotionType == QUEEN) }) {
                    assertEquals(referenceSee(current, move), StaticExchange.evaluate(current, move), "$move in ${current.toFen()}")
                    checked++
                }
            }
        }
        assertTrue(checked > 500, "$checked captures checked")
    }
}
