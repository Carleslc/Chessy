package core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RulesTest {

    private fun perft(board: Board, depth: Int): Long {
        if (depth == 0) return 1
        return board.legalMoves.fold(0L) { nodes, move -> nodes + perft(board.play(move), depth - 1) }
    }

    /** Known results from https://www.chessprogramming.org/Perft_Results */
    @Test
    fun perft() {
        val cases = listOf(
            Board.STARTING_FEN to listOf(20L, 400L, 8902L, 197281L),
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1" to listOf(48L, 2039L, 97862L),
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" to listOf(14L, 191L, 2812L, 43238L),
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1" to listOf(6L, 264L, 9467L),
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8" to listOf(44L, 1486L, 62379L),
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10" to listOf(46L, 2079L, 89890L)
        )
        for ((fen, expected) in cases) {
            val board = Board.fromFen(fen)
            for ((i, nodes) in expected.withIndex()) {
                assertEquals(nodes, perft(board, i + 1), "perft(${i + 1}) $fen")
            }
        }
    }

    @Test
    fun fenRoundTrip() {
        val fens = listOf(
            Board.STARTING_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3"
        )
        for (fen in fens) {
            assertEquals(fen, Board.fromFen(fen).toFen())
        }
    }

    private fun play(board: Board, vararg moves: String): Board = moves.fold(board) { current, text ->
        val move = current.parseMove(text)
        assertNotNull(move, "Illegal move $text in ${current.toFen()}")
        current.play(move)
    }

    @Test
    fun castling() {
        val board = play(Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"), "O-O", "O-O-O")
        assertEquals("2kr3r/8/8/8/8/8/8/R4RK1 w - - 2 2", board.toFen())

        // f1 is attacked by the rook on f8: no kingside castling
        val attacked = Board.fromFen("5r1k/7p/8/8/8/8/8/R3K2R w KQ - 0 1")
        val sans = attacked.legalMoves.map { attacked.toSan(it) }
        assertFalse("O-O" in sans)
        assertTrue("O-O-O" in sans)
    }

    @Test
    fun enPassant() {
        val board = play(Board.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3"), "exf6 e.p.")
        assertEquals("rnbqkbnr/ppp1p1pp/5P2/3p4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3", board.toFen())
    }

    @Test
    fun promotions() {
        val board = Board.fromFen("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val sans = board.legalMoves.map { board.toSan(it) }.toSet()
        assertTrue(setOf("a8=Q", "a8=R", "a8=B", "a8=N", "axb8=Q+", "axb8=R+", "axb8=B", "axb8=N").all { it in sans }, sans.toString())
        assertEquals("1N2k3/8/8/8/8/8/8/4K3 b - - 0 1", play(board, "axb8=N").toFen())
    }

    @Test
    fun san() {
        val board = Board.fromFen("4k3/8/8/8/1N3N2/8/8/R3K2R w KQ - 0 1")
        val sans = board.legalMoves.map { board.toSan(it) }.toSet()
        assertTrue("Nbd5" in sans && "Nfd5" in sans, sans.toString())
        assertTrue("O-O" in sans && "O-O-O" in sans)
        assertTrue("Ra8#" !in sans) // The king escapes through d7, e7, f7
        assertTrue("Ra8+" in sans)

        val mate = Board.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("Ra8#", mate.toSan(mate.parseMove("a1a8")!!))
    }

    @Test
    fun draws() {
        assertEquals(Status.STALEMATE, Board.fromFen("7k/5K2/6Q1/8/8/8/8/8 b - - 0 1").status)
        assertEquals(Status.INSUFFICIENT_MATERIAL, Board.fromFen("7k/8/8/8/8/8/8/1B5K w - - 0 1").status)
        // Bishops on squares of the same color (b1 and d7 are light squares)
        assertEquals(Status.INSUFFICIENT_MATERIAL, Board.fromFen("7k/3b4/8/8/8/8/8/1B5K w - - 0 1").status)
        assertEquals(Status.PLAYING, Board.fromFen("7k/4b3/8/8/8/8/8/1B5K w - - 0 1").status)
        assertEquals(Status.NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES, Board.fromFen("7k/8/8/8/8/8/8/R6K w - - 100 80").status)
        assertEquals(Status.CHECKMATE, Board.fromFen("R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1").status)
    }

    @Test
    fun pgn() {
        val start = Board.new()
        val pgn = Pgn(start, "Chessy", "Player")
        var board = start
        for (text in listOf("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#")) {
            val move = board.parseMove(text)!!
            pgn.add(board, move)
            board = board.play(move)
        }
        pgn.finish(board, board.status)
        val text = pgn.toString()
        assertTrue("[Result \"1-0\"]" in text)
        assertTrue(text.trim().endsWith("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0"), text)
    }
}
