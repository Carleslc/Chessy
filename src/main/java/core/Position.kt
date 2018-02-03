package core

import me.carleslc.kotlin.extensions.conversions.toInt

const val A_INT = 'a'.toInt()

data class Position(val row: Int, val column: Int) {

    companion object Conversion {

        fun fromAlgebraicChessNotation(position: String): Position = Position(8 - position[1].toString().toInt(), position[0].toInt() - A_INT)

        fun toAlgebraicChessNotation(position: Position): String = "${(A_INT + position.column).toChar()}${8 - position.row}"

    }

    /** Returns this position in algebraic chess notation. **/
    override fun toString(): String {
        return toAlgebraicChessNotation(this)
    }

}