package chessy.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameTest {

    private fun game(fen: String = Position.STARTING_FEN, vararg moves: String) = Game(fen).apply { moves.forEach { play(it) } }

    @Test
    fun checkmate() {
        val game = game(Position.STARTING_FEN, "f3", "e5", "g4", "Qh4#")
        assertEquals(GameStatus.CHECKMATE, game.status)
        assertEquals(Game.BLACK_WINS, game.result)
        assertFailsWith<IllegalStateException> { game.play("a3") }
    }

    @Test
    fun stalemate() {
        val game = game("7k/5K2/8/6Q1/8/8/8/8 w - - 0 1", "Qg6")
        assertEquals(GameStatus.STALEMATE, game.status)
        assertEquals(Game.DRAW, game.result)
    }

    @Test
    fun insufficientMaterial() {
        assertEquals(GameStatus.INSUFFICIENT_MATERIAL, Game("8/8/4k3/8/8/3K4/8/8 w - - 0 1").status)
        assertEquals(GameStatus.INSUFFICIENT_MATERIAL, Game("8/8/4k3/8/8/3K4/8/6N1 w - - 0 1").status)
        assertEquals(GameStatus.INSUFFICIENT_MATERIAL, Game("8/8/4k3/8/8/3K4/8/6B1 w - - 0 1").status)
        // Bishops on squares of the same color (g1 and e7 are dark squares)
        assertEquals(GameStatus.INSUFFICIENT_MATERIAL, Game("8/4b3/4k3/8/8/3K4/8/6B1 w - - 0 1").status)
        // Bishops on squares of different colors, two knights, or a pawn: mate is possible
        assertEquals(GameStatus.ONGOING, Game("8/3b4/4k3/8/8/3K4/8/6B1 w - - 0 1").status)
        assertEquals(GameStatus.ONGOING, Game("8/8/4k3/8/8/3K4/8/5NN1 w - - 0 1").status)
        assertEquals(GameStatus.ONGOING, Game("8/8/4k3/8/8/3K4/6P1/8 w - - 0 1").status)
        // Insufficient material after a capture
        assertEquals(GameStatus.INSUFFICIENT_MATERIAL, game("8/8/4k3/4r3/8/3K4/8/4R3 b - - 0 1", "Rxe1", "Kd2", "Re2+", "Kxe2").status)
    }

    @Test
    fun fiftyMoveRule() {
        val game = game("8/8/4k3/8/8/3K4/8/R7 w - - 98 80", "Ra2")
        assertEquals(GameStatus.ONGOING, game.status)
        game.play("Ke5")
        assertEquals(GameStatus.FIFTY_MOVE_RULE, game.status)
        assertEquals(Game.DRAW, game.result)
        // A checkmate on the last move is a checkmate
        assertEquals(GameStatus.CHECKMATE, game("6k1/5ppp/8/8/8/8/8/R5K1 w - - 99 80", "Ra8#").status)
    }

    @Test
    fun threefoldRepetition() {
        val game = game(Position.STARTING_FEN, "Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1")
        assertEquals(GameStatus.ONGOING, game.status)
        assertEquals(1, game.position.repetitions())
        game.play("Ng8") // Starting position for the third time
        assertEquals(2, game.position.repetitions())
        assertEquals(GameStatus.THREEFOLD_REPETITION, game.status)
        game.undo()
        assertEquals(GameStatus.ONGOING, game.status)
    }

    @Test
    fun repetitionNeedsSameCastlingRights() {
        // The kings move and come back, but castling rights are lost: it is not the same position
        val game = game("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "Kf1", "Kf8", "Ke1", "Ke8", "Kf1", "Kf8", "Ke1", "Ke8")
        assertEquals(1, game.position.repetitions())
        assertEquals(GameStatus.ONGOING, game.status)
    }

    @Test
    fun pgnExport() {
        val game = game(Position.STARTING_FEN, "e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#")
        game.headers["White"] = "Chessy"
        val pgn = game.toPgn()
        assertTrue(pgn.startsWith("[Event \"?\"]\n[Site \"?\"]\n[Date \"????.??.??\"]\n[Round \"?\"]\n[White \"Chessy\"]\n[Black \"?\"]\n[Result \"1-0\"]\n\n"), pgn)
        assertTrue(pgn.endsWith("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0\n"), pgn)
    }

    @Test
    fun pgnFromPosition() {
        val game = game("7r/8/2B2p2/p5Rp/4Qbk1/1P4B1/P4PKP/8 b - - 0 38", "fxg5", "Bd7#")
        val pgn = game.toPgn()
        assertTrue("[SetUp \"1\"]\n[FEN \"7r/8/2B2p2/p5Rp/4Qbk1/1P4B1/P4PKP/8 b - - 0 38\"]" in pgn, pgn)
        assertTrue(pgn.endsWith("38... fxg5 39. Bd7# 1-0\n"), pgn)
    }

    @Test
    fun pgnImport() {
        val pgn = """
            [Event "Test"]
            [White "A"]
            [Black "B"]
            [Result "0-1"]

            1.e4 {comment} e5 2. Nf3 (2. Bc4 Nf6 {variation}) Nc6 $1 3.Bb5!? a6 ; rest of line
            4. Ba4 Nf6 5. O-O Be7 0-1
        """.trimIndent()
        val game = Pgn.read(pgn)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7"), game.sanMoves())
        assertEquals("0-1", game.result) // Resignation
        assertEquals("A", game.headers["White"])

        val again = Pgn.read(game.toPgn())
        assertEquals(game.sanMoves(), again.sanMoves())
        assertEquals(game.position.toFen(), again.position.toFen())
        assertEquals("0-1", again.result)
    }

    @Test
    fun pgnImportErrors() {
        assertFailsWith<IllegalArgumentException> { Pgn.read("1. e4 e5 2. Ke3 *") }
        assertFailsWith<IllegalArgumentException> { Pgn.read("") }
    }

    /** Example games of the tools folder (multiple games, comments, annotations, positions from FEN). */
    @Test
    fun exampleGames() {
        val examples = File("../tools/examples").listFiles { file -> file.extension == "pgn" }!!.sorted()
        assertTrue(examples.isNotEmpty())
        val finalPositions = mutableListOf<String>()
        for (file in examples) {
            for (game in Pgn.readAll(file.readText())) {
                val again = Pgn.read(game.toPgn())
                assertEquals(game.sanMoves(), again.sanMoves(), file.name)
                assertEquals(game.result, again.result, file.name)
                finalPositions.add(game.position.toFen())
            }
        }
        assertEquals(9, finalPositions.size)
        assertEquals("5K2/r5p1/4kp2/7p/8/6n1/8/8 w - - 16 84", finalPositions[0])
        assertEquals("7r/3B4/8/p5pp/4Qbk1/1P4B1/P4PKP/8 b - - 1 39", finalPositions.last())
    }

    @Test
    fun chess960StartingPositions() {
        assertEquals(Position.STARTING_FEN, Position.chess960Fen(Position.CHESS960_STANDARD))
        assertEquals("bbqnnrkr/pppppppp/8/8/8/8/PPPPPPPP/BBQNNRKR w KQkq - 0 1", Position.chess960Fen(0))
        assertEquals("bqnbnrkr/pppppppp/8/8/8/8/PPPPPPPP/BQNBNRKR w KQkq - 0 1", Position.chess960Fen(1))
        assertEquals("qbbnrnkr/pppppppp/8/8/8/8/PPPPPPPP/QBBNRNKR w KQkq - 0 1", Position.chess960Fen(100))
        assertEquals("rbqknnbr/pppppppp/8/8/8/8/PPPPPPPP/RBQKNNBR w KQkq - 0 1", Position.chess960Fen(700))
        assertEquals("rkrnnqbb/pppppppp/8/8/8/8/PPPPPPPP/RKRNNQBB w KQkq - 0 1", Position.chess960Fen(959))
        // All different, and all with the king between the rooks
        assertEquals(960, (0..959).map { Position.chess960Fen(it) }.toSet().size)
    }

    @Test
    fun chess960Pgn() {
        // Position 360: the king on d1 castles kingside to g1, and its rook stays on f1
        val game = Game(Position.chess960Fen(360), chess960 = true)
        for (move in listOf("g3", "g6", "Qg2", "Qg7", "f3", "f6", "Bf2", "Bf7", "O-O")) game.play(move)
        assertEquals("nbrk1r1n/pppppbqp/5pp1/8/8/5PP1/PPPPPBQP/NBR2RKN b kq - 3 5", game.position.toFen())
        val pgn = game.toPgn()
        assertTrue("[Variant \"Chess960\"]" in pgn && "[FEN \"nbrkbrqn/" in pgn && "O-O" in pgn, pgn)
        val read = Pgn.read(pgn)
        assertTrue(read.chess960)
        assertEquals(game.position.toFen(), read.position.toFen())
    }
}
