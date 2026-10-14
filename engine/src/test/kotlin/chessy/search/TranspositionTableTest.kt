package chessy.search

import chessy.core.Move
import chessy.core.Notation
import chessy.core.Position
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranspositionTableTest {

    private val move = Move(12, 28, Move.DOUBLE_PAWN_PUSH)

    @Test
    fun storeAndProbe() {
        val table = TranspositionTable(1)
        val key = 0x123456789ABCDEFL
        assertEquals(TranspositionTable.NO_ENTRY, table.probe(key))
        table.store(key, move, -1234, 7, TranspositionTable.LOWER_BOUND)
        val entry = table.probe(key)
        assertEquals(move, TranspositionTable.moveOf(entry))
        assertEquals(-1234, TranspositionTable.scoreOf(entry))
        assertEquals(7, TranspositionTable.depthOf(entry))
        assertEquals(TranspositionTable.LOWER_BOUND, TranspositionTable.boundOf(entry))
        // Another key with the same index does not match
        assertEquals(TranspositionTable.NO_ENTRY, table.probe(key xor (1L shl 62)))
        table.clear()
        assertEquals(TranspositionTable.NO_ENTRY, table.probe(key))
    }

    @Test
    fun extremeScores() {
        val table = TranspositionTable(1)
        for (score in listOf(Score.MATE - 1, -Score.MATE + 1, Score.INFINITE, -Score.INFINITE, 0)) {
            table.store(42L, move, score, 1, TranspositionTable.EXACT)
            assertEquals(score, TranspositionTable.scoreOf(table.probe(42L)))
        }
    }

    @Test
    fun replacement() {
        val table = TranspositionTable(1)
        val key = 1L
        val other = key + table.entries.toLong() * 2 // Same index, different position
        table.store(key, move, 10, 8, TranspositionTable.EXACT)
        table.store(other, move, 20, 3, TranspositionTable.EXACT)
        assertEquals(10, TranspositionTable.scoreOf(table.probe(key)), "A shallower search does not replace a deeper one")
        table.newSearch()
        table.store(other, move, 20, 3, TranspositionTable.EXACT)
        assertEquals(20, TranspositionTable.scoreOf(table.probe(other)), "Entries of previous searches are replaced")
        // The same position keeps its move when the new search has none
        table.store(other, Move.NONE, 30, 4, TranspositionTable.UPPER_BOUND)
        assertEquals(move, TranspositionTable.moveOf(table.probe(other)))
    }

    @Test
    fun mateScoresAreRelativeToThePosition() {
        // Mate in 5 plies from the root, stored at ply 3: mate in 2 plies from the position
        assertEquals(Score.mateIn(2), TranspositionTable.scoreToTable(Score.mateIn(5), 3))
        assertEquals(Score.mateIn(5), TranspositionTable.scoreFromTable(Score.mateIn(2), 3))
        assertEquals(Score.matedIn(2), TranspositionTable.scoreToTable(Score.matedIn(5), 3))
        assertEquals(100, TranspositionTable.scoreToTable(100, 3))
    }

    @Test
    fun hashfull() {
        val table = TranspositionTable(1)
        table.newSearch()
        assertEquals(0, table.hashfull())
        for (key in 0L until table.entries) table.store(key, move, 0, 1, TranspositionTable.EXACT)
        assertEquals(1000, table.hashfull())
    }

    /** The table must not change what the search finds in tactical positions (verified with Stockfish). */
    @Test
    fun sameResultsWithAndWithoutTable() {
        val positions = mapOf(
            "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1" to "Ra8#",
            "r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 1" to "Nf6+",
            "r5rk/5p1p/5R2/4B3/8/8/7P/7K w - - 0 1" to "Ra6+",
            "6k1/pp4p1/2p5/2bp4/8/P5Pb/1P3rrP/2BRRN1K b - - 0 1" to "Rg1+",
            "r1b1kbnr/ppp1pppp/2n5/3q4/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 1" to "Nxd5",
        )
        for ((fen, expected) in positions) {
            for (table in listOf(null, TranspositionTable(16))) {
                val position = Position.fromFen(fen)
                val result = AlphaBetaSearch(transpositionTable = table).search(position, SearchLimits(depth = 6), AtomicBoolean())
                assertEquals(expected, Notation.toSan(position, result.bestMove), "$fen with table $table")
            }
        }
    }

    @Test
    fun tableReducesNodes() {
        fun nodes(table: TranspositionTable?): Long {
            var nodes = 0L
            AlphaBetaSearch(transpositionTable = table).search(Position.startingPosition(), SearchLimits(depth = 6), AtomicBoolean()) { nodes = it.nodes }
            return nodes
        }
        val withTable = nodes(TranspositionTable(16))
        val withoutTable = nodes(null)
        assertTrue(withTable < withoutTable, "$withTable >= $withoutTable")
    }
}
