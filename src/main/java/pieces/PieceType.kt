package pieces

import core.Position
import evaluation.*

enum class PieceType(val notation: String, val weight: Int, val preference: Int, var positionWeightTable: Array<IntArray>) {
    PAWN    ("P", 100, 4, PawnTable),
    KNIGHT  ("N", 325, 5, KnightTable),
    BISHOP  ("B", 350, 5, BishopTable),
    ROOK    ("R", 500, 2, Zero),
    QUEEN   ("Q", 900, 3, Zero),
    KING    ("K", 32767, 1, KingTable);

    fun positionWeightWhite(position: Position) = positionWeightTable[position.row][position.column]
    fun positionWeightBlack(position: Position) = positionWeightTable[7 - position.row][position.column]
}