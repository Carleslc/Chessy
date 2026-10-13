package core

import search.AlphaBeta
import search.MATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {

    private fun best(fen: String, depth: Int): Pair<String, Int> {
        val board = Board.fromFen(fen)
        val best = AlphaBeta(maxDepth = depth, maxMillis = 60_000, verbose = false).search(board)
        return board.toSan(best.move) to best.score
    }

    @Test
    fun mateInOneAtAnyDepth() {
        for (depth in 1..4) {
            val (move, score) = best("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", depth)
            assertEquals("Ra8#", move, "depth $depth")
            assertEquals(MATE - 1, score)
        }
    }

    @Test
    fun mateInTwo() {
        // 1. Rb7 Kg8 2. Ra8#
        val (_, score) = best("7k/8/6K1/8/8/8/R7/1R6 w - - 0 1", 4)
        assertTrue(score >= MATE - 3, "Mate in two expected, score $score")
    }

    @Test
    fun capturesHangingQueen() {
        assertEquals("Nxd5", best("r1b1kbnr/ppp1pppp/2n5/3q4/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 1", 3).first)
    }

    @Test
    fun avoidsLosingTheQueen() {
        // The queen is attacked by a pawn: it must move (Qxe5+ wins a pawn)
        val (move, score) = best("rnbqkbnr/pppp1ppp/8/4p3/3Q4/8/PPP1PPPP/RNB1KBNR w KQkq - 0 1", 3)
        assertTrue(move.startsWith("Q") && score > -100, "$move $score")
    }
}
