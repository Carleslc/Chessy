package core

import exceptions.NotationException
import me.carleslc.kotlin.extensions.conversions.toInt

data class Position(val row: Int, val column: Int) {

    companion object Conversion {

        fun fromAlgebraicChessNotation(position: String): Position {
            if (position.length < 2 || position[0] < 'a' || position[0] > 'h' || position[1] < '1' || position[1] > '8') {
                throw NotationException()
            }
            return Position(8 - position[1].toString().toInt(), position[0].toInt() - 'a'.toInt())
        }

        fun toAlgebraicChessNotation(position: Position): String = "${getAlgebraicChessColumn(position.column)}${8 - position.row}"

        fun getAlgebraicChessColumn(column: Int) = ('a'.toInt() + column).toChar()

    }

    val up by lazy {
        Position(row - 1, column)
    }

    val down by lazy {
        Position(row + 1, column)
    }

    val left by lazy {
        Position(row, column - 1)
    }

    val right by lazy {
        Position(row, column + 1)
    }

    val up_right by lazy {
        Position(row - 1, column + 1)
    }

    val up_left by lazy {
        Position(row - 1, column - 1)
    }

    val down_right by lazy {
        Position(row + 1, column + 1)
    }

    val down_left by lazy {
        Position(row + 1, column - 1)
    }

    fun isOutOfBounds() = row < 0 || row > 7 || column < 0 || column > 7

    /** Returns this position in algebraic chess notation. **/
    override fun toString(): String {
        return toAlgebraicChessNotation(this)
    }

}