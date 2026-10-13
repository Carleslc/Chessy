package chessy.search

import chessy.core.Notation
import chessy.core.Position
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GreedySearchTest {

    private fun best(fen: String, limits: SearchLimits = SearchLimits()): String {
        val position = Position.fromFen(fen)
        val result = GreedySearch().search(position, limits, AtomicBoolean())
        assertEquals(fen, position.toFen(), "The search must leave the position as it was")
        return Notation.toSan(position, result.bestMove)
    }

    @Test
    fun matesInOne() {
        assertEquals("Ra8#", best("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"))
    }

    @Test
    fun capturesTheMostValuablePiece() {
        assertEquals("Nxd5", best("r1b1kbnr/ppp1pppp/2n5/3q4/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 1"))
    }

    @Test
    fun avoidsStalemateWhenWinning() {
        // Qg6 or Qf7 would be stalemate
        val move = best("7k/8/5K2/6Q1/8/8/8/8 w - - 0 1")
        assertNotEquals("Qg6", move)
        assertNotEquals("Qf7", move)
    }

    @Test
    fun respectsSearchMoves() {
        val position = Position.startingPosition()
        val moves = listOf("a2a3", "h2h4").map { Notation.parseUci(position, it) }
        val result = GreedySearch().search(position, SearchLimits(searchMoves = moves), AtomicBoolean())
        assert(result.bestMove in moves)
    }

    @Test
    fun deterministic() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        assertEquals(best(fen), best(fen))
    }
}
