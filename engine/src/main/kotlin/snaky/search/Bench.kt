package snaky.search

import snaky.core.Position
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Searches a fixed set of positions to a fixed depth. The total number of nodes identifies the behavior
 * of the search (it changes when the search or the evaluation change) and the nodes per second measure its speed.
 */
object Bench {

    const val DEFAULT_DEPTH = 12

    val POSITIONS = listOf(
        Position.STARTING_FEN,
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
        "r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P4/2PBPN2/PP1N1PPP/R1BQ1RK1 w - - 0 9",
        "r2q1rk1/pp1bbppp/2n1pn2/3p4/3P4/2NBPN2/PP3PPP/R1BQ1RK1 w - - 4 10",
        "8/5pk1/6p1/3K4/8/6P1/5P2/8 w - - 0 50",
        "8/8/4k3/8/2p5/8/B2K4/8 w - - 0 60",
        "6k1/5p2/6p1/8/7p/8/6PP/6K1 b - - 0 40",
    )

    class Result(val nodes: Long, val millis: Long) {
        val nodesPerSecond: Long get() = if (millis > 0) nodes * 1000 / millis else 0
    }

    fun run(search: Search, depth: Int = DEFAULT_DEPTH, output: (String) -> Unit = {}): Result {
        var nodes = 0L
        val start = System.currentTimeMillis()
        for ((index, fen) in POSITIONS.withIndex()) {
            var positionNodes = 0L
            search.clear()
            val result = search.search(Position.fromFen(fen), SearchLimits(depth = depth), AtomicBoolean()) { positionNodes = it.nodes }
            nodes += positionNodes
            output("Position ${index + 1}/${POSITIONS.size}: ${result.bestMove} ${Score.toUci(result.score)}, $positionNodes nodes")
        }
        return Result(nodes, System.currentTimeMillis() - start)
    }
}
