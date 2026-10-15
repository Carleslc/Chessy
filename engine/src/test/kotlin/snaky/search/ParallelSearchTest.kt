package snaky.search

import snaky.core.Notation
import snaky.core.Position
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelSearchTest {

    @Test
    fun findsTheSameTactics() {
        val positions = mapOf(
            "r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 1" to "Nf6+",
            "r5rk/5p1p/5R2/4B3/8/8/7P/7K w - - 0 1" to "Ra6+",
            "r1b1kbnr/ppp1pppp/2n5/3q4/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 1" to "Nxd5",
        )
        val search = ParallelSearch(threads = 4)
        for ((fen, expected) in positions) {
            val position = Position.fromFen(fen)
            val result = search.search(position, SearchLimits(depth = 8), AtomicBoolean())
            assertEquals(fen, position.toFen())
            assertEquals(expected, Notation.toSan(position, result.bestMove), fen)
        }
    }

    @Test
    fun reportsTheNodesOfAllThreads() {
        var nodes = 0L
        val search = ParallelSearch(threads = 4)
        // Same depth (not time, so it does not depend on the load of the machine): the helpers add their nodes
        search.search(Position.startingPosition(), SearchLimits(depth = 10), AtomicBoolean()) { nodes = it.nodes }
        var singleNodes = 0L
        ParallelSearch(threads = 1).search(Position.startingPosition(), SearchLimits(depth = 10), AtomicBoolean()) { singleNodes = it.nodes }
        assertTrue(nodes > singleNodes, "$nodes <= $singleNodes")
    }

    @Test
    fun stopsAllThreads() {
        val search = ParallelSearch(threads = 4)
        val stop = AtomicBoolean()
        Thread { Thread.sleep(200); stop.set(true) }.start()
        val start = System.currentTimeMillis()
        search.search(Position.startingPosition(), SearchLimits(infinite = true), stop)
        assertTrue(System.currentTimeMillis() - start < 1000)
        // Can search again after stopping
        val result = search.search(Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"), SearchLimits(depth = 4), AtomicBoolean())
        assertEquals(Score.mateIn(1), result.score)
    }

    @Test
    fun changeThreads() {
        val search = ParallelSearch(threads = 1)
        search.threads = 3
        val result = search.search(Position.startingPosition(), SearchLimits(depth = 6), AtomicBoolean())
        assertTrue(result.bestMove != snaky.core.Move.NONE)
    }
}
