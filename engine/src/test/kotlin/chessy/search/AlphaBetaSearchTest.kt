package chessy.search

import chessy.core.Game
import chessy.core.Notation
import chessy.core.Position
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Positions verified with Stockfish 19. Limits by depth or nodes, so results are deterministic. */
class AlphaBetaSearchTest {

    private class Searched(val san: String, val score: Int, val infos: List<SearchInfo>)

    private fun search(position: Position, limits: SearchLimits, stop: AtomicBoolean = AtomicBoolean()): Searched {
        val fen = position.toFen()
        val infos = mutableListOf<SearchInfo>()
        val result = AlphaBetaSearch().search(position, limits, stop) { infos.add(it) }
        assertEquals(fen, position.toFen(), "The search must leave the position as it was")
        return Searched(Notation.toSan(position, result.bestMove), result.score, infos)
    }

    private fun search(fen: String, depth: Int) = search(Position.fromFen(fen), SearchLimits(depth = depth))

    @Test
    fun mateInOneAtAnyDepth() {
        // Even depths scored mates as losses in the first implementation (v0)
        for (depth in 1..6) {
            val result = search("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", depth)
            assertEquals("Ra8#", result.san, "depth $depth")
            assertEquals(Score.mateIn(1), result.score, "depth $depth")
        }
    }

    @Test
    fun mateInTwo() {
        val result = search("r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 1", 4)
        assertEquals("Nf6+", result.san)
        assertEquals(Score.mateIn(3), result.score)
        assertEquals("mate 2", Score.toUci(result.score))
    }

    @Test
    fun mateInThree() {
        val result = search("r5rk/5p1p/5R2/4B3/8/8/7P/7K w - - 0 1", 6)
        assertEquals("Ra6+", result.san)
        assertEquals(Score.mateIn(5), result.score)
    }

    @Test
    fun prefersTheShortestMate() {
        // Rg1+ mates in 2, Rxh2+ in 4
        val result = search("6k1/pp4p1/2p5/2bp4/8/P5Pb/1P3rrP/2BRRN1K b - - 0 1", 8)
        assertEquals("Rg1+", result.san)
        assertEquals(Score.mateIn(3), result.score)
    }

    @Test
    fun stopsDeepeningWhenTheMateIsProven() {
        val result = search("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", 20)
        assertEquals(1, result.infos.last().depth)
    }

    @Test
    fun capturesHangingPiece() {
        assertEquals("Nxd5", search("r1b1kbnr/ppp1pppp/2n5/3q4/8/2N5/PPPP1PPP/R1BQKBNR w KQkq - 0 1", 3).san)
    }

    @Test
    fun quiescenceAvoidsTheHorizonEffect() {
        // Qxd5 wins a pawn at depth 1, but exd5 wins the queen: the quiescence search sees the recapture
        for (depth in 1..3) {
            assertNotEquals("Qxd5", search("4k3/8/4p3/3p4/8/8/8/3QK3 w - - 0 1", depth).san, "depth $depth")
        }
    }

    @Test
    fun savesAttackedQueen() {
        val result = search("rnbqkbnr/pppp1ppp/8/4p3/3Q4/8/PPP1PPPP/RNB1KBNR w KQkq - 0 1", 4)
        assertTrue(result.san.startsWith("Q") && result.score > -100, "${result.san} ${result.score}")
    }

    @Test
    fun avoidsStalemateWhenWinning() {
        val position = Position.fromFen("7k/8/5K2/8/8/8/6Q1/8 w - - 0 1")
        val result = search(position, SearchLimits(depth = 4))
        assertTrue(Score.isMate(result.score) && result.score > 0)
    }

    @Test
    fun seeksDrawByRepetitionWhenLosing() {
        // Black is a queen down: Ng8 repeats the position for the third time
        val game = Game("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        listOf("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1").forEach { game.play(it) }
        val result = search(game.position, SearchLimits(depth = 4))
        assertEquals("Ng8", result.san)
        assertEquals(Score.DRAW, result.score)
    }

    @Test
    fun fiftyMoveRule() {
        // Any move but a pawn move draws by the fifty-move rule
        val result = search("8/8/8/4k3/8/8/4P3/R3K3 w - - 99 80", 4)
        assertTrue(result.san in listOf("e3", "e4"), result.san)
        assertTrue(result.score > 500)
    }

    @Test
    fun respectsSearchMoves() {
        val position = Position.startingPosition()
        val moves = listOf("a2a3", "h2h4").map { Notation.parseUci(position, it) }
        val result = AlphaBetaSearch().search(position, SearchLimits(depth = 4, searchMoves = moves), AtomicBoolean())
        assertTrue(result.bestMove in moves)
    }

    @Test
    fun respectsNodes() {
        val result = search(Position.startingPosition(), SearchLimits(nodes = 20_000))
        assertTrue(result.infos.last().nodes <= 20_000, "${result.infos.last().nodes}")
    }

    @Test
    fun respectsMoveTime() {
        val start = System.currentTimeMillis()
        search(Position.fromFen("r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P4/2PBPN2/PP1N1PPP/R1BQ1RK1 w - - 0 9"), SearchLimits(moveTime = 300))
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed in 100..450, "$elapsed ms")
    }

    @Test
    fun respectsClock() {
        val start = System.currentTimeMillis()
        search(Position.startingPosition(), SearchLimits(whiteTime = 3000, blackTime = 3000))
        assertTrue(System.currentTimeMillis() - start < 1500)
    }

    @Test
    fun stopsWhenAsked() {
        val stop = AtomicBoolean()
        Thread { Thread.sleep(200); stop.set(true) }.start()
        val start = System.currentTimeMillis()
        search(Position.startingPosition(), SearchLimits(infinite = true), stop)
        assertTrue(System.currentTimeMillis() - start < 1000)
    }

    @Test
    fun deterministic() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val first = search(fen, 4)
        val second = search(fen, 4)
        assertEquals(first.san, second.san)
        assertEquals(first.infos.last().nodes, second.infos.last().nodes)
    }

    @Test
    fun principalVariationIsLegal() {
        val position = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        val result = search(position, SearchLimits(depth = 5))
        val game = Game(position.toFen())
        for (move in result.infos.last().principalVariation) {
            game.play(move) // Throws if illegal
        }
        assertTrue(result.infos.last().principalVariation.size >= 5)
    }

    @Test
    fun iterativeDeepeningReportsEveryDepth() {
        val result = search(Position.startingPosition(), SearchLimits(depth = 5))
        assertEquals((1..5).toList(), result.infos.map { it.depth })
    }

    @Test
    fun mateLimit() {
        val result = search(Position.fromFen("r5rk/5p1p/5R2/4B3/8/8/7P/7K w - - 0 1"), SearchLimits(mate = 3))
        assertEquals("Ra6+", result.san)
        assertEquals("mate 3", Score.toUci(result.score))
    }
}
