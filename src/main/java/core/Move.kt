package core

import evaluation.MVVLVA
import pieces.Piece
import pieces.PieceType

/** Ordering bonus for queen promotions, searched right after the best captures. */
private const val PROMOTION_ORDER_SCORE = 20000

/**
 * A move of [piece] from [from] to [to], capturing [captured] (if any).
 * Pawns reaching the last row are promoted to [promotion]. Castling moves the king two columns and also the rook.
 */
data class Move(val from: Position,
                val to: Position,
                val piece: Piece,
                val captured: Piece? = null,
                val promotion: PieceType? = null,
                val castling: Castling? = null,
                val enPassant: Boolean = false) {

    fun isCapture() = captured != null

    fun isPromotion() = promotion != null

    fun isCastling() = castling != null

    /** Position of the captured piece, which is not the target position when capturing en passant. */
    val capturedPosition: Position get() = if (enPassant) Position(from.row, to.column) else to

    /** Cheap heuristic score to search the most promising moves first. */
    val orderScore: Int get() = MVVLVA.getScore(this) + if (promotion == PieceType.QUEEN) PROMOTION_ORDER_SCORE else 0

    /** Long algebraic notation used to type moves: e2e4, e7e8q. */
    val notation: String get() = "$from$to" + (promotion?.notation?.toLowerCase() ?: "")

    override fun toString(): String {
        if (castling != null) {
            return "$piece ${castling.san}"
        }
        val target = if (captured != null) "$captured [$to]" else to.toString()
        val promoted = if (promotion != null) " =${promotion.notation}" else ""
        return "$piece [$from] -> $target$promoted"
    }

}
