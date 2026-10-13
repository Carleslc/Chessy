package core

import evaluation.MVVLVA
import pieces.Piece
import pieces.PieceType

/** Ordering bonus for promotions, searched right after the best captures. */
private const val PROMOTION_ORDER_SCORE = 20000

data class Move(val from: Position, val to: Position, val piece: Piece, val captured: Piece? = null) {

    fun isCapture() = captured != null

    /** Pawns are always promoted to queens. */
    fun isPromotion() = piece.type == PieceType.PAWN && to.opponentRow(piece.player)

    /** Cheap heuristic score to search the most promising moves first. */
    val orderScore: Int get() = MVVLVA.getScore(this) + if (isPromotion()) PROMOTION_ORDER_SCORE else 0

    override fun toString(): String {
        val target = if (captured != null) "$captured [$to]" else to.toString()
        return "$piece [$from] -> $target"
    }

}
