package core

import pieces.Piece
import pieces.isEmpty
import pieces.isEmptyOrOpponent

class Move(val from: Position, val to: Position, val currentBoard: Board) {

    val nextBoard: Board by lazy {
        currentBoard.applied(this)
    }

    val target: Piece? = currentBoard[to]

    fun isCapture(player: Player): Boolean {
        val piece = currentBoard[to]
        return if (piece.isEmpty()) false else piece!!.player != player
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Move

        if (from != other.from) return false
        if (to != other.to) return false

        return true
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        return result
    }

    override fun toString(): String {

        fun toString(piece: Piece?, position: Position): String {
            return if (piece != null) "$piece [$position]" else position.toString()
        }

        val pieceFrom = currentBoard[from]
        val pieceTo = currentBoard[to]

        return "${toString(pieceFrom, from)} -> ${toString(pieceTo, to)}"
    }

}