package core

import pieces.Piece
import pieces.isEmpty

val moveComparator = compareBy<Move>({ it.isCapture() }, { EVALUATOR.getMaxScore(it.nextBoard) }, { it.origin?.type?.preference })

data class Move(val from: Position, val to: Position, private val currentBoard: Board) : Comparable<Move> {

    val nextBoard: Board by lazy {
        currentBoard.applied(this)
    }

    val origin by lazy {
        currentBoard[from]
    }

    val target by lazy {
        currentBoard[to]
    }

    fun isCapture() = !target.isEmpty()

    fun isCapture(player: Player): Boolean {
        return if (target.isEmpty()) false else target!!.player != player
    }

    override fun compareTo(other: Move) = moveComparator.compare(this, other)

    override fun toString(): String {

        fun toString(piece: Piece?, position: Position): String {
            return if (piece != null) "$piece [$position]" else position.toString()
        }

        val pieceFrom = currentBoard[from]
        val pieceTo = currentBoard[to]

        return "${toString(pieceFrom, from)} -> ${toString(pieceTo, to)}"
    }

}