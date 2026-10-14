package chessy.eval

import chessy.core.BISHOP
import chessy.core.BLACK
import chessy.core.Color
import chessy.core.KING
import chessy.core.KNIGHT
import chessy.core.PAWN
import chessy.core.PieceType
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.ROOK
import chessy.core.WHITE
import chessy.core.flipRank
import chessy.core.forEachSquare
import chessy.core.popCount

/**
 * Baseline evaluation: material and piece-square tables, tapered between middlegame and endgame.
 *
 * Tables are the "Simplified Evaluation Function" of Tomasz Michniewski
 * (https://www.chessprogramming.org/Simplified_Evaluation_Function), with an endgame table for the king
 * and for the pawns (the closer to promotion, the better). Values will be tuned in phase 6.
 */
class MaterialEvaluator : Evaluator {

    override val name = "Material"

    /** Same as the sum of the terms of [explain], with plain integers to avoid allocations in the search. */
    override fun evaluate(position: Position): Int {
        var middlegame = 0
        var endgame = 0
        for (color in WHITE..BLACK) {
            val sign = if (color == WHITE) 1 else -1
            for (type in PAWN..KING) {
                position.pieces(color, type).forEachSquare { square ->
                    val index = tableIndex(color, square)
                    middlegame += sign * (PIECE_VALUES[type].middlegame + MIDDLEGAME_TABLES[type][index])
                    endgame += sign * (PIECE_VALUES[type].endgame + ENDGAME_TABLES[type][index])
                }
            }
            if (hasBishopPair(position, color)) {
                middlegame += sign * BISHOP_PAIR.middlegame
                endgame += sign * BISHOP_PAIR.endgame
            }
        }
        val white = TaperedScore(middlegame, endgame).taper(GamePhase.of(position))
        return if (position.sideToMove == WHITE) white else -white
    }

    override fun explain(position: Position): EvalTrace = EvalTrace(name, GamePhase.of(position), position.sideToMove).apply {
        add("Material", material(position, WHITE), material(position, BLACK))
        add("Piece squares", placement(position, WHITE), placement(position, BLACK))
        add("Bishop pair", bishopPair(position, WHITE), bishopPair(position, BLACK))
    }

    private fun material(position: Position, color: Color): TaperedScore {
        var score = TaperedScore.ZERO
        for (type in PAWN..QUEEN) {
            score += PIECE_VALUES[type] * popCount(position.pieces(color, type))
        }
        return score
    }

    private fun placement(position: Position, color: Color): TaperedScore {
        var middlegame = 0
        var endgame = 0
        for (type in PAWN..KING) {
            position.pieces(color, type).forEachSquare { square ->
                middlegame += MIDDLEGAME_TABLES[type][tableIndex(color, square)]
                endgame += ENDGAME_TABLES[type][tableIndex(color, square)]
            }
        }
        return TaperedScore(middlegame, endgame)
    }

    private fun bishopPair(position: Position, color: Color): TaperedScore =
        if (hasBishopPair(position, color)) BISHOP_PAIR else TaperedScore.ZERO

    private fun hasBishopPair(position: Position, color: Color) = popCount(position.pieces(color, BISHOP)) >= 2

    /** Tables are written from the point of view of white, with rank 8 first: black squares are mirrored. */
    private fun tableIndex(color: Color, square: Int) = if (color == WHITE) flipRank(square) else square

    companion object {
        /** Material values in centipawns, indexed by [PieceType]. */
        val PIECE_VALUES = arrayOf(
            TaperedScore(100, 120), // Pawn
            TaperedScore(320, 320), // Knight
            TaperedScore(330, 330), // Bishop
            TaperedScore(500, 500), // Rook
            TaperedScore(900, 900), // Queen
            TaperedScore(0, 0),     // King: checkmate is scored by the search
        )

        val BISHOP_PAIR = TaperedScore(30, 50)

        // Piece-square tables from the point of view of white: a8..h8 first, a1..h1 last

        private val PAWN_TABLE = intArrayOf(
             0,  0,  0,  0,  0,  0,  0,  0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
             5,  5, 10, 25, 25, 10,  5,  5,
             0,  0,  0, 20, 20,  0,  0,  0,
             5, -5,-10,  0,  0,-10, -5,  5,
             5, 10, 10,-20,-20, 10, 10,  5,
             0,  0,  0,  0,  0,  0,  0,  0,
        )

        private val PAWN_ENDGAME_TABLE = intArrayOf(
             0,  0,  0,  0,  0,  0,  0,  0,
            80, 80, 80, 80, 80, 80, 80, 80,
            50, 50, 50, 50, 50, 50, 50, 50,
            30, 30, 30, 30, 30, 30, 30, 30,
            15, 15, 15, 15, 15, 15, 15, 15,
             5,  5,  5,  5,  5,  5,  5,  5,
             0,  0,  0,  0,  0,  0,  0,  0,
             0,  0,  0,  0,  0,  0,  0,  0,
        )

        private val KNIGHT_TABLE = intArrayOf(
            -50,-40,-30,-30,-30,-30,-40,-50,
            -40,-20,  0,  0,  0,  0,-20,-40,
            -30,  0, 10, 15, 15, 10,  0,-30,
            -30,  5, 15, 20, 20, 15,  5,-30,
            -30,  0, 15, 20, 20, 15,  0,-30,
            -30,  5, 10, 15, 15, 10,  5,-30,
            -40,-20,  0,  5,  5,  0,-20,-40,
            -50,-40,-30,-30,-30,-30,-40,-50,
        )

        private val BISHOP_TABLE = intArrayOf(
            -20,-10,-10,-10,-10,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5, 10, 10,  5,  0,-10,
            -10,  5,  5, 10, 10,  5,  5,-10,
            -10,  0, 10, 10, 10, 10,  0,-10,
            -10, 10, 10, 10, 10, 10, 10,-10,
            -10,  5,  0,  0,  0,  0,  5,-10,
            -20,-10,-10,-10,-10,-10,-10,-20,
        )

        private val ROOK_TABLE = intArrayOf(
             0,  0,  0,  0,  0,  0,  0,  0,
             5, 10, 10, 10, 10, 10, 10,  5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
             0,  0,  0,  5,  5,  0,  0,  0,
        )

        private val QUEEN_TABLE = intArrayOf(
            -20,-10,-10, -5, -5,-10,-10,-20,
            -10,  0,  0,  0,  0,  0,  0,-10,
            -10,  0,  5,  5,  5,  5,  0,-10,
             -5,  0,  5,  5,  5,  5,  0, -5,
              0,  0,  5,  5,  5,  5,  0, -5,
            -10,  5,  5,  5,  5,  5,  0,-10,
            -10,  0,  5,  0,  0,  0,  0,-10,
            -20,-10,-10, -5, -5,-10,-10,-20,
        )

        private val KING_TABLE = intArrayOf(
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -30,-40,-40,-50,-50,-40,-40,-30,
            -20,-30,-30,-40,-40,-30,-30,-20,
            -10,-20,-20,-20,-20,-20,-20,-10,
             20, 20,  0,  0,  0,  0, 20, 20,
             20, 30, 10,  0,  0, 10, 30, 20,
        )

        private val KING_ENDGAME_TABLE = intArrayOf(
            -50,-40,-30,-20,-20,-30,-40,-50,
            -30,-20,-10,  0,  0,-10,-20,-30,
            -30,-10, 20, 30, 30, 20,-10,-30,
            -30,-10, 30, 40, 40, 30,-10,-30,
            -30,-10, 30, 40, 40, 30,-10,-30,
            -30,-10, 20, 30, 30, 20,-10,-30,
            -30,-30,  0,  0,  0,  0,-30,-30,
            -50,-30,-30,-30,-30,-30,-30,-50,
        )

        internal val MIDDLEGAME_TABLES = arrayOf(PAWN_TABLE, KNIGHT_TABLE, BISHOP_TABLE, ROOK_TABLE, QUEEN_TABLE, KING_TABLE)
        internal val ENDGAME_TABLES = arrayOf(PAWN_ENDGAME_TABLE, KNIGHT_TABLE, BISHOP_TABLE, ROOK_TABLE, QUEEN_TABLE, KING_ENDGAME_TABLE)
    }
}
