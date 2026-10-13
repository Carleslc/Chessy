package pieces

import core.Board
import core.Move
import core.Player
import core.Position

sealed class Piece(val type: PieceType, val player: Player) {

    /** Pseudo-legal moves of this piece (they may leave the own king in check). */
    abstract fun availableMovesOn(from: Position, board: Board): List<Move>

    /** Adds the move if [to] is inside the board and its target satisfies [check]. Returns whether it was added. */
    protected fun MutableList<Move>.addIfValid(from: Position, to: Position, board: Board,
                                               check: (Piece?) -> Boolean = { it.isEmptyOrOpponent(player) }): Boolean {
        if (to.isOutOfBounds()) {
            return false
        }
        val target = board[to]
        if (!check(target)) {
            return false
        }
        add(Move(from, to, this@Piece, target))
        return true
    }

    protected fun MutableList<Move>.addLinear(from: Position, board: Board) {
        addDirection(from, board) { up }
        addDirection(from, board) { down }
        addDirection(from, board) { left }
        addDirection(from, board) { right }
    }

    protected fun MutableList<Move>.addDiagonal(from: Position, board: Board) {
        addDirection(from, board) { up_left }
        addDirection(from, board) { up_right }
        addDirection(from, board) { down_left }
        addDirection(from, board) { down_right }
    }

    /** Slides through empty positions until finding a piece, which can be captured if it is from the opponent. */
    private fun MutableList<Move>.addDirection(from: Position, board: Board, direction: Position.() -> Position) {
        var to = from.direction()
        while (addIfValid(from, to, board) { it.isEmpty() }) {
            to = to.direction()
        }
        addIfValid(from, to, board) { it.isOpponent(player) }
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

fun Piece?.isEmpty() = this == null

fun Piece?.isEmptyOrOpponent(player: Player) = this == null || player != this.player

fun Piece?.isOpponent(player: Player) = this != null && player != this.player

fun Piece?.isOwn(player: Player) = this != null && player == this.player

class King(player: Player): Piece(PieceType.KING, player) {

    override fun availableMovesOn(from: Position, board: Board): List<Move> {
        val moves = mutableListOf<Move>()
        moves.addIfValid(from, from.down_left, board)
        moves.addIfValid(from, from.down, board)
        moves.addIfValid(from, from.down_right, board)
        moves.addIfValid(from, from.left, board)
        moves.addIfValid(from, from.right, board)
        moves.addIfValid(from, from.up_left, board)
        moves.addIfValid(from, from.up, board)
        moves.addIfValid(from, from.up_right, board)
        // TODO: Castling
        return moves
    }

}

class Queen(player: Player): Piece(PieceType.QUEEN, player) {

    override fun availableMovesOn(from: Position, board: Board): List<Move> {
        val moves = mutableListOf<Move>()
        moves.addLinear(from, board)
        moves.addDiagonal(from, board)
        return moves
    }

}

class Pawn(player: Player): Piece(PieceType.PAWN, player) {

    override fun availableMovesOn(from: Position, board: Board): List<Move> {

        fun isFirstMove() = (player == Player.WHITE && from.row == 6) || (player == Player.BLACK && from.row == 1)

        val white = player == Player.WHITE
        val moves = mutableListOf<Move>()
        if (moves.addIfValid(from, if (white) from.up else from.down, board) { it.isEmpty() } && isFirstMove()) {
            moves.addIfValid(from, if (white) from.up.up else from.down.down, board) { it.isEmpty() }
        }
        moves.addIfValid(from, if (white) from.up_left else from.down_left, board) { it.isOpponent(player) }
        moves.addIfValid(from, if (white) from.up_right else from.down_right, board) { it.isOpponent(player) }
        // TODO: En passant

        return moves
    }

}

class Rook(player: Player): Piece(PieceType.ROOK, player) {

    override fun availableMovesOn(from: Position, board: Board): List<Move> {
        val moves = mutableListOf<Move>()
        moves.addLinear(from, board)
        return moves
    }

}

class Bishop(player: Player): Piece(PieceType.BISHOP, player) {

    override fun availableMovesOn(from: Position, board: Board): List<Move> {
        val moves = mutableListOf<Move>()
        moves.addDiagonal(from, board)
        return moves
    }

}

class Knight(player: Player): Piece(PieceType.KNIGHT, player) {

    override fun availableMovesOn(from: Position, board: Board): List<Move> {
        val moves = mutableListOf<Move>()
        moves.addIfValid(from, from.up.up.left, board)
        moves.addIfValid(from, from.up.up.right, board)
        moves.addIfValid(from, from.up.left.left, board)
        moves.addIfValid(from, from.up.right.right, board)
        moves.addIfValid(from, from.down.down.left, board)
        moves.addIfValid(from, from.down.down.right, board)
        moves.addIfValid(from, from.down.left.left, board)
        moves.addIfValid(from, from.down.right.right, board)
        return moves
    }

}
