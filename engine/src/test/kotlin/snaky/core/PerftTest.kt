package snaky.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Known perft results from https://www.chessprogramming.org/Perft_Results */
class PerftTest {

    private class Case(val name: String, val fen: String, val nodes: List<Long>, val fastDepth: Int)

    private val cases = listOf(
        Case("Initial position", Position.STARTING_FEN,
            listOf(20, 400, 8_902, 197_281, 4_865_609, 119_060_324), fastDepth = 5),
        Case("Kiwipete", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            listOf(48, 2_039, 97_862, 4_085_603, 193_690_690), fastDepth = 4),
        Case("Position 3", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            listOf(14, 191, 2_812, 43_238, 674_624, 11_030_083), fastDepth = 5),
        Case("Position 4", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            listOf(6, 264, 9_467, 422_333, 15_833_292), fastDepth = 4),
        Case("Position 4 mirrored", "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1",
            listOf(6, 264, 9_467, 422_333, 15_833_292), fastDepth = 4),
        Case("Position 5", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            listOf(44, 1_486, 62_379, 2_103_487, 89_941_194), fastDepth = 4),
        Case("Position 6", "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            listOf(46, 2_079, 89_890, 3_894_594, 164_075_551), fastDepth = 4),
    ).map { Case(it.name, it.fen, it.nodes.map(Number::toLong), it.fastDepth) }

    private val slow = System.getProperty("snaky.slow") == "true"

    @Test
    fun perft() {
        for (case in cases) {
            val position = Position.fromFen(case.fen)
            val maxDepth = if (slow) case.nodes.size else case.fastDepth
            for (depth in 1..maxDepth) {
                val start = System.currentTimeMillis()
                val nodes = Perft(position).perft(depth)
                val millis = System.currentTimeMillis() - start
                println("${case.name} perft($depth) = $nodes ($millis ms)")
                assertEquals(case.nodes[depth - 1], nodes, "${case.name} perft($depth)")
            }
            assertEquals(case.fen, position.toFen(), "${case.name}: position restored after perft")
        }
    }

    @Test
    fun divideSumsPerft() {
        val position = Position.fromFen(cases[1].fen)
        val divide = Perft(position).divide(3)
        assertEquals(48, divide.size)
        assertEquals(97_862, divide.sumOf { it.second })
    }

    @Test
    fun parallelPerftEqualsSequential() {
        for (case in cases) {
            val position = Position.fromFen(case.fen)
            for (depth in 1..3) {
                assertEquals(case.nodes[depth - 1], Perft.parallelPerft(position, depth, threads = 4), "${case.name} perft($depth)")
            }
            val sequential = Perft(position).divide(3).toMap()
            assertEquals(sequential, Perft.parallelDivide(position, 3, threads = 4).toMap(), case.name)
        }
    }
}
