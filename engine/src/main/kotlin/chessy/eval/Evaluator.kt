package chessy.eval

import chessy.core.BISHOP
import chessy.core.BLACK
import chessy.core.KNIGHT
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.ROOK
import chessy.core.WHITE
import chessy.core.popCount

/**
 * Static evaluation of a position, without searching.
 *
 * Evaluators are interchangeable: the search only depends on this interface,
 * so different strategies (material, space, neural networks...) can be compared with the same search.
 */
interface Evaluator {

    /** Name to select the evaluator (UCI option `Evaluator`). */
    val name: String

    /** Score in centipawns from the point of view of the side to move (positive is better for it). */
    fun evaluate(position: Position): Int

    /** Score of each term of the evaluation, to debug, analyze and tune it. */
    fun explain(position: Position): EvalTrace

    /** Penalty of a draw for the engine in centipawns (positive: avoid draws, negative: seek them). */
    val contempt: Int get() = 0

    companion object {
        /** Available evaluators by name, the default one first. */
        val all: Map<String, () -> Evaluator> = linkedMapOf(
            "Space" to { SpaceEvaluator() },
            "Material" to ::MaterialEvaluator,
        )

        const val DEFAULT = "Space"

        fun named(name: String): Evaluator =
            all.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.invoke()
                ?: throw IllegalArgumentException("Unknown evaluator: $name (${all.keys.joinToString()})")
    }
}

/**
 * A score with a middlegame and an endgame value, interpolated by the [GamePhase] of the position
 * (tapered evaluation, https://www.chessprogramming.org/Tapered_Eval).
 */
data class TaperedScore(val middlegame: Int, val endgame: Int) {
    operator fun plus(other: TaperedScore) = TaperedScore(middlegame + other.middlegame, endgame + other.endgame)
    operator fun minus(other: TaperedScore) = TaperedScore(middlegame - other.middlegame, endgame - other.endgame)
    operator fun times(factor: Int) = TaperedScore(middlegame * factor, endgame * factor)

    fun taper(phase: Int): Int = (middlegame * phase + endgame * (GamePhase.MIDDLEGAME - phase)) / GamePhase.MIDDLEGAME

    override fun toString() = "($middlegame, $endgame)"

    companion object {
        val ZERO = TaperedScore(0, 0)
    }
}

/** Game phase from the non-pawn material: [MIDDLEGAME] with all the pieces, 0 with only kings and pawns. */
object GamePhase {
    const val MIDDLEGAME = 24

    fun of(position: Position): Int {
        var phase = 0
        for (color in listOf(WHITE, BLACK)) {
            phase += popCount(position.pieces(color, KNIGHT)) + popCount(position.pieces(color, BISHOP)) +
                popCount(position.pieces(color, ROOK)) * 2 + popCount(position.pieces(color, QUEEN)) * 4
        }
        return minOf(phase, MIDDLEGAME) // Promotions can exceed it
    }
}

/** Breakdown of an evaluation: the score of each term for each color. */
class EvalTrace(val evaluator: String, val phase: Int, val sideToMove: Int) {

    class Term(val name: String, val white: TaperedScore, val black: TaperedScore)

    /** Dynamic value of a piece: material, placement, mobility, outposts, open files, trapped... */
    class PieceValue(val piece: Char, val square: String, val value: TaperedScore, val mobility: Int)

    val terms = mutableListOf<Term>()

    /** Dynamic value of each piece (except pawns and kings), if the evaluator computes it. */
    val pieces = mutableListOf<PieceValue>()

    fun add(name: String, white: TaperedScore, black: TaperedScore) {
        terms.add(Term(name, white, black))
    }

    /** Total from the point of view of white (tapered once, like the evaluation, to avoid rounding errors). */
    val total: Int get() = terms.fold(TaperedScore.ZERO) { sum, term -> sum + term.white - term.black }.taper(phase)

    /** Total from the point of view of the side to move, as [Evaluator.evaluate]. */
    val score: Int get() = if (sideToMove == WHITE) total else -total

    override fun toString(): String = buildString {
        appendLine("$evaluator evaluation (phase $phase/${GamePhase.MIDDLEGAME}, centipawns from the point of view of white)")
        appendLine()
        appendLine("%-20s %18s %18s %18s".format("Term", "White (mg, eg)", "Black (mg, eg)", "Total"))
        for (term in terms) {
            val difference = term.white - term.black
            appendLine("%-20s %18s %18s %18s".format(term.name, term.white, term.black, "$difference = ${difference.taper(phase)}"))
        }
        appendLine()
        appendLine("Total: $total (white), $score (side to move)")
        if (pieces.isNotEmpty()) {
            appendLine()
            appendLine("Dynamic value of the pieces (material + placement + activity):")
            appendLine()
            appendLine("%-8s %8s %16s %8s".format("Piece", "Mobility", "(mg, eg)", "Value"))
            for (piece in pieces) {
                appendLine("%-8s %8d %16s %8d".format("${piece.piece} ${piece.square}", piece.mobility, piece.value, piece.value.taper(phase)))
            }
        }
    }
}
