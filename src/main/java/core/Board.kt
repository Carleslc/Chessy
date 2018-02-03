package core

import me.carleslc.kotlin.extensions.arrays.Matrix
import me.carleslc.kotlin.extensions.arrays.copy
import me.carleslc.kotlin.extensions.arrays.matrixOfNulls
import pieces.Piece
import java.util.*

class Board private constructor(val board: Matrix<Piece?>, var turn: Player) {

    companion object Factory {

        fun new(): Board {
            val board: Matrix<Piece?> = matrixOfNulls(8, 8)
            fillRow(board, 0, Player.BLACK)
            fillPawns(board, 1, Player.BLACK)
            // board[2][*] to board[5][*] are empty positions
            fillPawns(board, 6, Player.WHITE)
            fillRow(board, 7, Player.WHITE)
            return Board(board, Player.WHITE)
        }

        private fun fillRow(board: Matrix<Piece?>, row: Int, player: Player) {
            board[row][0] = Rook(player)
            board[row][1] = Knight(player)
            board[row][2] = Bishop(player)
            board[row][3] = Queen(player)
            board[row][4] = King(player)
            board[row][5] = Bishop(player)
            board[row][6] = Knight(player)
            board[row][7] = Rook(player)
        }

        private fun fillPawns(board: Matrix<Piece?>, row: Int, player: Player) {
            for (column in 0..7) {
                board[row][column] = Pawn(player)
            }
        }

    }

    fun isEndGame(): Boolean {
        TODO()
    }

    fun apply(move: Move): Board {
        return apply {
            with (move) {
                val original: Piece? = board[from.row][from.column]
                board[from.row][from.column] = null
                board[to.row][to.column] = original
                turn = turn.opponent()
            }
        }
    }

    fun isValid(move: Move): Boolean {
        TODO()
    }

    fun copy() = Board(board.copy(), turn)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Board

        if (!Arrays.deepEquals(board, other.board)) return false

        return true
    }

    override fun hashCode(): Int {
        return Arrays.deepHashCode(board)
    }

}