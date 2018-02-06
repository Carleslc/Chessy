package core

import me.carleslc.kotlin.extensions.arrays.Matrix
import me.carleslc.kotlin.extensions.arrays.copy
import me.carleslc.kotlin.extensions.arrays.matrixOfNulls
import pieces.Piece
import java.util.*

internal class ChessboardMatrix private constructor(private val board: Matrix<Piece?>) : Chessboard() {

    companion object Factory {

        fun empty() = ChessboardMatrix(matrixOfNulls(8, 8))

    }

    override fun getPieces(turn: Player): Set<Map.Entry<Position, Piece>> {
        val pieces: MutableSet<Map.Entry<Position, Piece>> = mutableSetOf()
        for (i in rows) {
            for (j in columns) {
                val p = board[i][j]
                if (p != null && p.player == turn) {
                    pieces.add(AbstractMap.SimpleImmutableEntry(Position(i, j), p))
                }
            }
        }
        return pieces
    }

    override fun copy() = ChessboardMatrix(board.copy())

    override operator fun get(row: Int): Array<Piece?> = board[row]

    override operator fun get(p: Position): Piece? = board[p.row][p.column]

    override operator fun get(row: Int, column: Int): Piece? = board[row][column]

    override operator fun set(p: Position, e: Piece?) {
        board[p.row][p.column] = e
    }

    override operator fun set(row: Int, column: Int, e: Piece?) {
        board[row][column] = e
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChessboardMatrix

        if (!Arrays.deepEquals(board, other.board)) return false

        return true
    }

    override fun hashCode(): Int {
        return Arrays.deepHashCode(board)
    }

}