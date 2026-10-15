package snaky.eval

import snaky.core.Position
import snaky.search.Bench
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialEvaluatorTest {

    private val evaluator = MaterialEvaluator()

    /** Same position with the colors swapped and the board flipped vertically. */
    private fun mirror(fen: String): String {
        val fields = fen.split(" ")
        val board = fields[0].split("/").reversed().joinToString("/") { rank -> rank.map { if (it.isLetter()) (if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar()) else it }.joinToString("") }
        val side = if (fields[1] == "w") "b" else "w"
        val castling = if (fields[2] == "-") "-" else fields[2].map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.sorted().joinToString("")
        val enPassant = if (fields[3] == "-") "-" else "${fields[3][0]}${if (fields[3][1] == '3') '6' else '3'}"
        return "$board $side $castling $enPassant ${fields[4]} ${fields[5]}"
    }

    @Test
    fun startingPositionIsBalanced() {
        assertEquals(0, evaluator.evaluate(Position.startingPosition()))
    }

    @Test
    fun symmetric() {
        for (fen in Bench.POSITIONS) {
            val mirrored = mirror(fen)
            assertEquals(evaluator.evaluate(Position.fromFen(fen)), evaluator.evaluate(Position.fromFen(mirrored)), "$fen / $mirrored")
        }
    }

    @Test
    fun sideToMovePointOfView() {
        // White is a queen up
        assertTrue(evaluator.evaluate(Position.fromFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")) > 800)
        assertTrue(evaluator.evaluate(Position.fromFen("4k3/8/8/8/8/8/8/3QK3 b - - 0 1")) < -800)
    }

    @Test
    fun explainMatchesEvaluate() {
        for (fen in Bench.POSITIONS) {
            val position = Position.fromFen(fen)
            assertEquals(evaluator.evaluate(position), evaluator.explain(position).score, fen)
        }
    }

    @Test
    fun taperedByPhase() {
        assertEquals(GamePhase.MIDDLEGAME, GamePhase.of(Position.startingPosition()))
        assertEquals(0, GamePhase.of(Position.fromFen("4k3/pppp4/8/8/8/8/PPPP4/4K3 w - - 0 1")))
        // The king prefers the center in the endgame and the corner in the middlegame
        val endgameCenter = evaluator.evaluate(Position.fromFen("8/8/8/4k3/8/8/8/R3K3 w - - 0 1"))
        val endgameCorner = evaluator.evaluate(Position.fromFen("7k/8/8/8/8/8/8/R3K3 w - - 0 1"))
        assertTrue(endgameCorner > endgameCenter)
    }
}
