package snaky.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotationTest {

    /** SAN of a move given in UCI notation. */
    private fun san(fen: String, uci: String): String {
        val position = Position.fromFen(fen)
        return Notation.toSan(position, Notation.parseUci(position, uci))
    }

    /** UCI notation of a move given in SAN. */
    private fun uci(fen: String, san: String): String = Notation.parseSan(Position.fromFen(fen), san).toString()

    @Test
    fun pawnMovesAndCaptures() {
        assertEquals("d4", san(Position.STARTING_FEN, "d2d4"))
        assertEquals("exd5", san("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"))
        assertEquals("exd6", san("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6"))
        assertEquals("e5d6", uci("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "exd6 e.p."))
        assertEquals("e5d6", uci("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "exd6"))
    }

    @Test
    fun pieceMovesAndCaptures() {
        assertEquals("Nf3", san(Position.STARTING_FEN, "g1f3"))
        assertEquals("Rxf3", san("4k3/8/8/8/8/5n1R/8/4K3 w - - 0 1", "h3f3"))
        assertEquals("h3f3", uci("4k3/8/8/8/8/5n1R/8/4K3 w - - 0 1", "Rxf3"))
        assertEquals("h3f3", uci("4k3/8/8/8/8/5n1R/8/4K3 w - - 0 1", "Rf3"))
    }

    @Test
    fun disambiguation() {
        // Knights on g8 and d7 can go to f6: the file is enough
        val knights = "4k1n1/3n4/8/8/8/8/8/4K3 b - - 0 1"
        assertEquals("Ngf6", san(knights, "g8f6"))
        assertEquals("Ndf6", san(knights, "d7f6"))
        assertEquals("g8f6", uci(knights, "Ngf6"))
        assertEquals("d7f6", uci(knights, "Ndf6"))
        assertFailsWith<IllegalArgumentException> { uci(knights, "Nf6") }

        // Rooks on e1 and e4 can go to e2: same file, so the rank is used
        val rooks = "7k/8/8/8/4R3/8/8/K3R3 w - - 0 1"
        assertEquals("R1e2", san(rooks, "e1e2"))
        assertEquals("R4e2", san(rooks, "e4e2"))
        assertEquals("e4e2", uci(rooks, "R4e2"))

        // Queens on e4, h4 and h1 can go to e1: neither file nor rank are enough for h4
        val queens = "8/2k5/8/8/4Q2Q/8/8/K6Q w - - 0 1"
        assertEquals("Qh4e1", san(queens, "h4e1"))
        assertEquals("Qee1", san(queens, "e4e1"))
        assertEquals("Q1e1", san(queens, "h1e1"))
        assertEquals("h4e1", uci(queens, "Qh4e1"))
    }

    @Test
    fun checksAndMates() {
        assertEquals("Nf3+", san("8/8/8/6k1/8/8/8/K5N1 w - - 0 1", "g1f3"))
        assertEquals("Qh1#", san("8/8/8/8/8/5k1K/8/3q4 b - - 0 1", "d1h1"))
        assertEquals("Ra8#", san("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", "a1a8"))
        assertEquals("a1a8", uci("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", "Ra8#"))
        assertEquals("a1a8", uci("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", "Ra8++"))
        assertEquals("a1a8", uci("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", "Ra8!!"))
    }

    @Test
    fun castling() {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals("O-O", san(fen, "e1g1"))
        assertEquals("O-O-O", san(fen, "e1c1"))
        assertEquals("e1g1", uci(fen, "O-O"))
        assertEquals("e1c1", uci(fen, "O-O-O"))
        assertEquals("e1c1", uci(fen, "0-0-0"))
    }

    @Test
    fun promotions() {
        val fen = "1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1"
        assertEquals("a8=Q", san(fen, "a7a8q"))
        assertEquals("axb8=N", san(fen, "a7b8n"))
        assertEquals("axb8=Q+", san(fen, "a7b8q"))
        assertEquals("a7b8n", uci(fen, "axb8=N"))
        assertEquals("a7b8q", uci(fen, "axb8=Q+"))
        assertEquals("a7a8r", uci(fen, "a8=R"))
        assertEquals("a7a8q", uci(fen, "a8")) // Queen by default
    }

    @Test
    fun invalidMoves() {
        for (move in listOf("Nf4", "e5", "O-O", "Zf3", "", "e2e5", "Ke2")) {
            assertFailsWith<IllegalArgumentException>(move) { Notation.parseMove(Position.startingPosition(), move) }
        }
    }

    @Test
    fun parseMoveAcceptsBothNotations() {
        val position = Position.startingPosition()
        assertEquals("g1f3", Notation.parseMove(position, "Nf3").toString())
        assertEquals("g1f3", Notation.parseMove(position, "g1f3").toString())
        assertEquals("e2e4", Notation.parseMove(position, "e4").toString())
    }
}
