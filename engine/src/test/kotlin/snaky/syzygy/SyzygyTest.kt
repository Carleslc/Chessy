package snaky.syzygy

import snaky.core.Notation
import snaky.core.Position
import snaky.search.AlphaBetaSearch
import snaky.search.Score
import snaky.search.SearchLimits
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Needs the tablebases of up to 5 pieces in tools/syzygy (see tools/README.md); without them the tests are skipped.
 * Expected values from python-chess (tools/syzygy_check.py compares thousands of random positions).
 */
class SyzygyTest {

    private val path = File("../tools/syzygy")
    private val tablebases: Syzygy? = if (File(path, "KQvK.rtbw").exists()) Syzygy(path.path) else null

    private fun probe(fen: String): Pair<Int?, Int?> {
        val position = Position.fromFen(fen)
        return tablebases!!.probeWdl(position) to tablebases.probeDtz(position)
    }

    @Test
    fun probes() {
        if (tablebases == null) return
        assertEquals(5, tablebases.maxPieces)
        assertEquals(0 to 0, probe("8/8/8/8/8/2k5/2P5/2K5 w - - 0 1"))
        assertEquals(2 to 13, probe("8/8/8/4k3/8/8/3QK3/8 w - - 0 1"))
        assertEquals(-2 to -1, probe("8/8/8/8/8/3k4/8/R2K1r2 w - - 0 1"))
        // Each side to move, and with pawns of both colors
        assertEquals(2 to 1, probe("8/8/8/8/8/8/3Pk3/1K6 w - - 0 1"))
        val (wdl, dtz) = probe("8/8/4k3/4p3/4P3/4K3/8/8 b - - 0 1")
        assertEquals(0, wdl)
        assertEquals(0, dtz)
        // Not in the tables: castling rights or too many pieces
        assertNull(probe("4k3/8/8/8/8/8/8/4K2R w K - 0 1").first)
        assertNull(probe("4k3/8/8/8/8/8/PPPP4/4K3 w - - 0 1").first)
    }

    @Test
    fun cursedWin() {
        if (tablebases == null) return
        // KBN vs K too far from the mate for the fifty-move rule
        val wdl = probe("8/8/8/8/8/8/8/KBN1k3 w - - 99 1").first
        assertTrue(wdl == 2 || wdl == 1)
    }

    @Test
    fun searchConvertsTheWin() {
        if (tablebases == null) return
        // KBN vs K: only the moves that keep the win with the shortest distance to zero are searched
        val position = Position.fromFen("8/8/8/4k3/8/8/8/1KBN4 w - - 0 1")
        val search = AlphaBetaSearch().apply { this.tablebases = this@SyzygyTest.tablebases }
        val result = search.search(position, SearchLimits(depth = 6), AtomicBoolean())
        val dtz = tablebases.probeDtz(position)!!
        position.makeMove(result.bestMove)
        assertEquals(-(dtz - 1), tablebases.probeDtz(position), Notation.toSan(Position.fromFen("8/8/8/4k3/8/8/8/1KBN4 w - - 0 1"), result.bestMove))
        assertTrue(result.score > Score.TABLEBASE_WIN - 100 && !Score.isMate(result.score), "${result.score}")
        // A drawn endgame is a draw, whatever the material says (two knights cannot mate)
        val draw = AlphaBetaSearch().apply { this.tablebases = this@SyzygyTest.tablebases }
            .search(Position.fromFen("8/8/8/8/3k4/8/3K4/4N1N1 b - - 0 1"), SearchLimits(depth = 6), AtomicBoolean())
        assertEquals(Score.DRAW, draw.score)
    }
}
