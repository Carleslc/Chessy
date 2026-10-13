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

    fun create(player: Player): Piece = when (this) {
        PAWN -> Pawn(player)
        KNIGHT -> Knight(player)
        BISHOP -> Bishop(player)
        ROOK -> Rook(player)
        QUEEN -> Queen(player)
        KING -> King(player)
    }

    companion object {
        /** Pieces a pawn can be promoted to, the most valuable first. */
        val PROMOTIONS = listOf(QUEEN, KNIGHT, ROOK, BISHOP)

        fun fromNotation(notation: Char): PieceType = values().firstOrNull { it.notation[0] == notation.toUpperCase() }
            ?: throw IllegalArgumentException("Invalid piece: $notation")
    }
}
