package pieces

import core.Player
import core.Position
import evaluation.*

enum class PieceType(val notation: String, val weight: Int, val positionWeightTable: Array<IntArray>) {
    PAWN    ("P", 100, PawnTable),
    KNIGHT  ("N", 325, KnightTable),
    BISHOP  ("B", 350, BishopTable),
    ROOK    ("R", 500, Zero),
    QUEEN   ("Q", 900, Zero),
    KING    ("K", 0, KingTable); // Both players always have a king, so it does not count as material

    /** Tables are written from the white point of view (row 0 is the opponent row). */
    fun positionWeight(position: Position, player: Player, table: Array<IntArray> = positionWeightTable): Int {
        val row = if (player == Player.WHITE) position.row else 7 - position.row
        return table[row][position.column]
    }
}
