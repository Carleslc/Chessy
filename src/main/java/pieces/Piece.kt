package pieces

import core.Board
import core.Move
import core.Player
import core.Position
import me.carleslc.kotlin.extensions.standard.alsoIfTrue
import me.carleslc.kotlin.extensions.standard.isNull

sealed class Piece(val type: PieceType, val player: Player) {

    abstract fun availableMovesOn(from: Position, board: Board): Set<Move>

    protected fun addIfValid(moves: MutableSet<Move>, move: Move,
                             check: Move.() -> Boolean = { target.isEmptyOrOpponent(player) }): Boolean {
        return let { (!move.to.isOutOfBounds() && move.check()).alsoIfTrue { moves.add(move) } }
    }

    protected fun addLinear(moves: MutableSet<Move>, from: Position, board: Board) {
        addDirection(moves, from, board) { up }
        addDirection(moves, from, board) { down }
        addDirection(moves, from, board) { left }
        addDirection(moves, from, board) { right }
    }

    protected fun addDiagonal(moves: MutableSet<Move>, from: Position, board: Board) {
        addDirection(moves, from, board) { up_left }
        addDirection(moves, from, board) { up_right }
        addDirection(moves, from, board) { down_left }
        addDirection(moves, from, board) { down_right }
    }

    private fun addDirection(moves: MutableSet<Move>, from: Position, board: Board, direction: Position.() -> Position) {
        val isEmpty: Move.() -> Boolean = { target.isEmpty() }

        var move = Move(from, from.direction(), board)
        while (addIfValid(moves, move, isEmpty)) {
            move = Move(from, move.to.direction(), board)
        }
        addIfValid(moves, move) { target.isEmptyOrOpponent(player) }
    }

    override fun toString() = "$player ${type.notation}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Piece

        if (type != other.type) return false
        if (player != other.player) return false

        return true
    }

    override fun hashCode(): Int = 31 * type.hashCode() + player.hashCode()

}

fun Piece?.isEmpty() = isNull()

fun Piece?.isEmptyOrOpponent(player: Player) = isEmpty() || player != this!!.player

fun Piece?.isOwn(player: Player) = !isEmptyOrOpponent(player)

class King(player: Player): Piece(PieceType.KING, player) {

    override fun availableMovesOn(from: Position, board: Board): Set<Move> {
        val moves: MutableSet<Move> = mutableSetOf()
        addIfValid(moves, Move(from, from.down_left, board))
        addIfValid(moves, Move(from, from.down, board))
        addIfValid(moves, Move(from, from.down_right, board))
        addIfValid(moves, Move(from, from.left, board))
        addIfValid(moves, Move(from, from.right, board))
        addIfValid(moves, Move(from, from.up_left, board))
        addIfValid(moves, Move(from, from.up, board))
        addIfValid(moves, Move(from, from.up_right, board))
        // TODO: Castling
        return moves
    }

}

class Queen(player: Player): Piece(PieceType.QUEEN, player) {

    override fun availableMovesOn(from: Position, board: Board): Set<Move> {
        val moves: MutableSet<Move> = mutableSetOf()
        addLinear(moves, from, board)
        addDiagonal(moves, from, board)
        return moves
    }

}

class Pawn(player: Player): Piece(PieceType.PAWN, player) {

    override fun availableMovesOn(from: Position, board: Board): Set<Move> {

        fun isFirstMove() = (player == Player.WHITE && from.row == 6) || (player == Player.BLACK && from.row == 1)

        val moves: MutableSet<Move> = mutableSetOf()
        addIfValid(moves, Move(from, if (player == Player.WHITE) from.up else from.down, board)) { target.isEmpty() }
        addIfValid(moves, Move(from, if (player == Player.WHITE) from.up.up else from.down.down, board)) { target.isEmpty() && isFirstMove() }
        addIfValid(moves, Move(from, if (player == Player.WHITE) from.up_left else from.down_left, board)) { isCapture(player) }
        addIfValid(moves, Move(from, if (player == Player.WHITE) from.up_right else from.down_right, board)) { isCapture(player) }
        // TODO: En passant

        return moves
    }

}

class Rook(player: Player): Piece(PieceType.ROOK, player) {

    override fun availableMovesOn(from: Position, board: Board): Set<Move> {
        val moves: MutableSet<Move> = mutableSetOf()
        addLinear(moves, from, board)
        return moves
    }

}

class Bishop(player: Player): Piece(PieceType.BISHOP, player) {

    override fun availableMovesOn(from: Position, board: Board): Set<Move> {
        val moves: MutableSet<Move> = mutableSetOf()
        addDiagonal(moves, from, board)
        return moves
    }

}

class Knight(player: Player): Piece(PieceType.KNIGHT, player) {

    override fun availableMovesOn(from: Position, board: Board): Set<Move> {
        val moves: MutableSet<Move> = mutableSetOf()
        addIfValid(moves, Move(from, from.up.up.left, board))
        addIfValid(moves, Move(from, from.up.up.right, board))
        addIfValid(moves, Move(from, from.up.left.left, board))
        addIfValid(moves, Move(from, from.up.right.right, board))
        addIfValid(moves, Move(from, from.down.down.left, board))
        addIfValid(moves, Move(from, from.down.down.right, board))
        addIfValid(moves, Move(from, from.down.left.left, board))
        addIfValid(moves, Move(from, from.down.right.right, board))
        return moves
    }

}