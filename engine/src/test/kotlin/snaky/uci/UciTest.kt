package snaky.uci

import snaky.core.MoveGenerator
import snaky.core.Notation
import snaky.core.Position
import snaky.search.ParallelSearch
import snaky.search.Strength
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UciTest {

    private val output: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val uci = Uci(output = { output.add(it) })

    /** Sends commands and waits for the search to finish. Returns the output lines. */
    private fun send(vararg commands: String): List<String> {
        output.clear()
        for (command in commands) uci.handle(command)
        uci.waitForSearch()
        return output.toList()
    }

    private fun bestMove(lines: List<String>): String = lines.last { it.startsWith("bestmove") }.split(" ")[1]

    @Test
    fun handshake() {
        val lines = send("uci")
        assertTrue(lines[0].startsWith("id name Snaky v"))
        assertTrue(lines[1].startsWith("id author "))
        assertTrue("option name Move Overhead type spin default 100 min 0 max 5000" in lines)
        assertEquals("uciok", lines.last())
        assertEquals(listOf("readyok"), send("isready"))
    }

    /** Total of the output of the eval command. */
    private fun total(lines: List<String>): String = lines.joinToString("\n").lines().first { it.startsWith("Total:") }

    @Test
    fun weightsOption() {
        val file = kotlin.io.path.createTempFile(suffix = ".weights").toFile()
        try {
            assertTrue("option name Weights type string default <empty>" in send("uci"))
            send("position fen 4k3/8/8/8/8/2N5/8/4K3 w - - 0 1")
            val default = total(send("eval"))
            file.writeText("pieceValues.knight.mg = 1000\npieceValues.knight.eg = 1000\n")
            send("setoption name Weights value ${file.path}")
            val heavy = total(send("eval"))
            assertTrue(heavy != default, heavy)
            send("setoption name Weights value <empty>")
            assertEquals(default, total(send("eval")))
            assertTrue(send("setoption name Weights value /missing.weights").single().startsWith("info string Error"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun positionWithMoves() {
        send("position startpos moves e2e4 e7e5 g1f3")
        assertTrue(send("d").any { it.contains("FEN: rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2") })

        send("position fen 6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1 moves a1b1")
        assertTrue(send("d").any { it.contains("FEN: 6k1/5ppp/8/8/8/8/5PPP/1R4K1 b - - 1 1") })
    }

    @Test
    fun goReturnsLegalMove() {
        val lines = send("position startpos moves e2e4", "go wtime 1000 btime 1000 winc 10 binc 10")
        assertTrue(lines.any { it.startsWith("info depth") })
        val position = Position.startingPosition().also { it.makeMove(Notation.parseUci(it, "e2e4")) }
        val legal = MoveGenerator.legalMoves(position).toList().map { it.toString() }
        assertTrue(bestMove(lines) in legal)
    }

    @Test
    fun matesInOne() {
        assertEquals("a1a8", bestMove(send("position fen 6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", "go depth 1")))
    }

    @Test
    fun infiniteWaitsForStop() {
        output.clear()
        uci.handle("position startpos")
        uci.handle("go infinite")
        Thread.sleep(200)
        uci.handle("isready")
        assertTrue(output.none { it.startsWith("bestmove") }, output.toString())
        assertTrue("readyok" in output)
        uci.handle("stop")
        assertTrue(output.last().startsWith("bestmove"), output.toString())
    }

    @Test
    fun ponderWaitsForPonderhit() {
        assertTrue("option name Ponder type check default false" in send("uci"))
        assertEquals(emptyList(), send("setoption name Ponder value true"))
        output.clear()
        uci.handle("position startpos")
        // With 1 second on the clock, the move takes a fraction of it: but while pondering the time does not count
        uci.handle("go ponder wtime 1000 btime 1000")
        Thread.sleep(500)
        assertTrue(output.none { it.startsWith("bestmove") })
        val start = System.currentTimeMillis()
        uci.handle("ponderhit")
        uci.waitForSearch()
        // After the ponderhit the search goes on with its own time, a fraction of the clock
        assertTrue(System.currentTimeMillis() - start < 900)
        assertTrue(output.last().startsWith("bestmove"))
    }

    @Test
    fun stopWhilePondering() {
        output.clear()
        uci.handle("position startpos")
        uci.handle("go ponder wtime 60000 btime 60000")
        Thread.sleep(100)
        uci.handle("stop") // The opponent played another move
        assertTrue(output.last().startsWith("bestmove"), output.toString())
    }

    @Test
    fun searchMoves() {
        val lines = send("position startpos", "go depth 1 searchmoves a2a3 h2h3")
        assertTrue(bestMove(lines) in listOf("a2a3", "h2h3"))
    }

    @Test
    fun noLegalMoves() {
        assertEquals("(none)", bestMove(send("position fen 7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", "go")))
        assertEquals("(none)", bestMove(send("position fen R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1", "go")))
    }

    @Test
    fun errorsDoNotStopTheEngine() {
        assertTrue(send("foo").single().startsWith("info string Unknown command"))
        assertTrue(send("position startpos moves e2e5").single().startsWith("info string Error: Illegal move"))
        assertTrue(send("position fen invalid").single().startsWith("info string Error"))
        assertTrue(send("setoption name Foo value 1").single().startsWith("info string Error: Unknown option"))
        assertTrue(send("setoption name Move Overhead value 99999").single().startsWith("info string Error"))
        assertEquals(emptyList(), send("setoption name move overhead value 200"))
        assertTrue(send("go depth x").single().startsWith("info string Error"))
        assertEquals(listOf("readyok"), send("isready"))
    }

    @Test
    fun repetitionHistoryIsKept() {
        // Black is a queen down: Ng8 repeats the initial position for the third time, a draw,
        // which is better than any other move. It is only found if the moves of the position command are kept.
        send("setoption name Style value Material") // Without contempt: the draw scores 0
        val lines = send("position fen rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 moves g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1", "go depth 1")
        assertEquals("f6g8", bestMove(lines))
        assertTrue(lines.any { it.contains("score cp 0") })
    }

    @Test
    fun options() {
        val lines = send("uci")
        assertTrue("option name Style type combo default Space var Space var Full var Material var Greedy" in lines, lines.toString())
        assertTrue("option name Greedy Depth type spin default 2 min 1 max 4" in lines)
        assertTrue("option name Hash type spin default 256 min 1 max 1024" in lines)
        assertTrue("option name Threads type spin default ${ParallelSearch.DEFAULT_THREADS} min 1 max 256" in lines)
        assertEquals(emptyList(), send("setoption name Threads value 2", "setoption name Hash value 16"))
        assertTrue(bestMove(send("position startpos", "go depth 5")).length == 4)
        send("setoption name Threads value 1")
        assertEquals(emptyList(), send("setoption name Style value greedy", "setoption name Greedy Depth value 1"))
        // Greedy depth 1 grabs the pawn defended by another pawn
        assertEquals("d1d5", bestMove(send("position fen 4k3/8/4p3/3p4/8/8/8/3QK3 w - - 0 1", "go")))
        send("setoption name Style value Material")
        assertTrue(bestMove(send("go depth 4")) != "d1d5")
        assertTrue(send("eval").single().startsWith("Material evaluation"))
        send("setoption name Style value Space")
        assertTrue(bestMove(send("go depth 4")) != "d1d5")
        assertTrue(send("eval").single().startsWith("Space evaluation"))
        send("setoption name Style value Full")
        assertTrue(bestMove(send("go depth 4")) != "d1d5")
        assertTrue(send("eval").single().startsWith("Full evaluation"))
        assertTrue(send("setoption name Style value Foo").single().startsWith("info string Error"))
    }

    @Test
    fun ownBook() {
        assertTrue("option name OwnBook type check default false" in send("uci"))
        // Without book the engine searches
        assertTrue(send("position startpos", "go depth 1").none { it == "info string book move" })
        send("setoption name OwnBook value true", "setoption name Opening value French")
        val first = send("position startpos", "go wtime 1000 btime 1000")
        assertTrue("info string book move" in first)
        assertEquals("e2e4", bestMove(first))
        assertEquals("e7e6", bestMove(send("position startpos moves e2e4", "go wtime 1000 btime 1000")))
        // Out of the book (and of the French), the engine searches
        assertTrue("info string book move" !in send("position startpos moves h2h4 a7a5", "go depth 2"))
        send("setoption name OwnBook value false", "setoption name Opening value All")
    }

    @Test
    fun chess960() {
        send("setoption name UCI_Chess960 value true")
        // In Chess960 castling is the king taking its rook: O-O is d1f1 here (the rook stays on f1) and O-O-O d1c1 (they swap)
        val fen = "nbrkbrqn/pppppppp/8/8/8/8/PPPPPPPP/NBRKBRQN w FCfc - 0 1"
        val moves = "g2g3 g7g6 g1g2 g8g7 f2f3 f7f6 e1f2 e8f7"
        send("position fen $fen moves $moves")
        val legal = send("moves").single().split(" ")
        assertTrue("O-O" in legal && "O-O-O" in legal, legal.toString())
        send("position fen $fen moves $moves d1f1")
        assertTrue(send("d").single().contains("FEN: nbrk1r1n/pppppbqp/5pp1/8/8/5PP1/PPPPPBQP/NBR2RKN b kq - 3 5"), send("d").toString())
        // The engine sends castling in the same notation
        assertTrue(send("position fen nbrkbrqn/pppppppp/8/8/8/8/PPPPPPPP/NBRKBRQN w FCfc - 0 1", "go depth 3").last().startsWith("bestmove"))
        send("setoption name UCI_Chess960 value false")
    }

    @Test
    fun limitStrength() {
        val lines = send("uci")
        assertTrue("option name UCI_LimitStrength type check default false" in lines)
        assertTrue("option name UCI_Elo type spin default 1500 min 900 max 2800" in lines)
        // At 1500 Elo the search stops after a few hundred nodes, with one thread whatever the Threads option
        send("setoption name UCI_LimitStrength value true", "setoption name UCI_Elo value 1500")
        val info = send("position startpos", "go wtime 60000 btime 60000").last { it.startsWith("info depth") }
        val nodes = info.split(" ").let { it[it.indexOf("nodes") + 1].toLong() }
        assertTrue(nodes <= Strength.settingFor(1500).nodes!! * 2, info)
        // At 900 Elo it plays the greedy search
        send("setoption name UCI_Elo value 900")
        assertTrue(send("eval").single().startsWith("Material evaluation"))
        send("setoption name UCI_LimitStrength value false")
    }

    @Test
    fun mateSearch() {
        val lines = send("position fen r5rk/5p1p/5R2/4B3/8/8/7P/7K w - - 0 1", "go mate 3")
        assertTrue(lines.any { it.contains("score mate 3") })
        assertEquals("f6a6", bestMove(lines))
    }

    @Test
    fun evalAndBench() {
        assertTrue(send("setoption name Style value Material", "position startpos", "eval").single().contains("Total: 0 (white)"))
        assertTrue(send("setoption name Style value Space", "eval").single().contains("Dynamic value of the pieces"))
        val bench = send("bench 2")
        assertTrue(bench.any { it.startsWith("Nodes: ") })
        assertTrue(bench.any { it.startsWith("NPS: ") })
    }

    @Test
    fun quit() {
        uci.handle("position startpos")
        uci.handle("go infinite")
        assertEquals(false, uci.handle("quit"))
        assertTrue(output.last().startsWith("bestmove"))
    }
}
