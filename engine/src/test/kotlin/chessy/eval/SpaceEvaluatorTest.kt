package chessy.eval

import chessy.core.Position
import chessy.search.Bench
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceEvaluatorTest {

    private val evaluator = SpaceEvaluator()

    /** Same position with the colors swapped and the board flipped vertically. */
    private fun mirror(fen: String): String {
        val fields = fen.split(" ")
        val board = fields[0].split("/").reversed().joinToString("/") { rank -> rank.map { if (it.isLetter()) (if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar()) else it }.joinToString("") }
        val side = if (fields[1] == "w") "b" else "w"
        val castling = if (fields[2] == "-") "-" else fields[2].map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.sorted().joinToString("")
        val enPassant = if (fields[3] == "-") "-" else "${fields[3][0]}${if (fields[3][1] == '3') '6' else '3'}"
        return "$board $side $castling $enPassant ${fields[4]} ${fields[5]}"
    }

    private val positions = Bench.POSITIONS + listOf(
        "rnbqkbnr/pp3ppp/4p3/2ppP3/3P4/8/PPP2PPP/RNBQKBNR w KQkq c6 0 4",
        "7B/6p1/5p2/8/8/8/8/k3K3 w - - 0 1",
        "4k3/8/8/3N4/4P3/8/8/4K3 w - - 0 1",
        "r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 7",
    )

    /** Score of a term for white minus black (tapered). */
    private fun term(fen: String, name: String): Int {
        val trace = evaluator.explain(Position.fromFen(fen))
        val term = trace.terms.first { it.name == name }
        return (term.white - term.black).taper(trace.phase)
    }

    @Test
    fun symmetric() {
        for (fen in positions) {
            val mirrored = mirror(fen)
            assertEquals(evaluator.evaluate(Position.fromFen(fen)), evaluator.evaluate(Position.fromFen(mirrored)), "$fen / $mirrored")
        }
    }

    @Test
    fun explainMatchesEvaluate() {
        for (fen in positions) {
            val position = Position.fromFen(fen)
            assertEquals(evaluator.evaluate(position), evaluator.explain(position).score, fen)
        }
    }

    @Test
    fun evaluationIsRepeatable() {
        // Internal arrays are reused: evaluating other positions in between must not change the result
        val first = evaluator.evaluate(Position.fromFen(positions[1]))
        positions.forEach { evaluator.evaluate(Position.fromFen(it)) }
        assertEquals(first, evaluator.evaluate(Position.fromFen(positions[1])))
    }

    @Test
    fun startingPositionOnlyTempo() {
        val trace = evaluator.explain(Position.startingPosition())
        assertEquals(SpaceWeights.DEFAULT.tempo.taper(GamePhase.MIDDLEGAME), trace.score)
    }

    @Test
    fun advancedPawnChainGainsSpace() {
        // French Advance: the pawns on d4 and e5 give white more space
        assertTrue(term("rnbqkbnr/pp3ppp/4p3/2ppP3/3P4/8/PPP2PPP/RNBQKBNR w KQkq c6 0 4", "Space") > 0)
    }

    @Test
    fun trappedPiece() {
        // The bishop on h8 is blocked by its own pawn on g7
        assertTrue(term("7B/6P1/5p2/8/8/8/8/k3K3 w - - 0 1", "Trapped pieces") < 0)
    }

    @Test
    fun outpost() {
        assertTrue(term("4k3/8/8/3N4/4P3/8/8/4K3 w - - 0 1", "Outposts") > 0)
        // A pawn on c7 can attack d6... and c6 can chase the knight from d5: no outpost
        assertEquals(0, term("4k3/2p5/8/3N4/4P3/8/8/4K3 w - - 0 1", "Outposts"))
    }

    @Test
    fun activePiecesAreWorthMore() {
        // Knight in the center against a knight in the corner
        val pieces = evaluator.explain(Position.fromFen("n3k3/8/8/8/3N4/8/8/4K3 w - - 0 1")).pieces
        val center = pieces.first { it.square == "d4" }
        val corner = pieces.first { it.square == "a8" }
        assertTrue(center.mobility > corner.mobility)
        assertTrue(center.value.taper(0) > corner.value.taper(0))
    }

    @Test
    fun threats() {
        // A black knight attacked by a white pawn, and undefended
        assertTrue(term("4k3/8/8/3n4/4P3/8/8/4K3 w - - 0 1", "Threats") > 0)
    }

    @Test
    fun passedPawnsAreWorthMoreWhenAdvanced() {
        val sixth = term("4k3/8/1P6/8/8/8/8/4K3 w - - 0 1", "Passed pawns")
        val third = term("4k3/8/8/8/8/1P6/8/4K3 w - - 0 1", "Passed pawns")
        assertTrue(sixth > third, "$sixth <= $third")
    }

    @Test
    fun kingSafety() {
        // The black queen and rook attack the white king, without pawn shield
        assertTrue(term("4k3/8/8/8/8/6q1/5r2/6K1 w - - 0 1", "King safety") < 0)
    }

    @Test
    fun contempt() {
        assertEquals(SpaceWeights.DEFAULT.contempt, evaluator.contempt)
        assertEquals(0, MaterialEvaluator().contempt)
    }
}
