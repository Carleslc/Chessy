package core

import pieces.Piece

private val SQUARES = 0..7

internal abstract class Chessboard {

    val rows = SQUARES
    val columns = SQUARES

    abstract fun getPieces(turn: Player): Set<Map.Entry<Position, Piece>>

    abstract fun copy(): Chessboard

    abstract operator fun get(row: Int): Array<Piece?>

    abstract operator fun get(p: Position): Piece?

    abstract operator fun get(row: Int, column: Int): Piece?

    abstract operator fun set(p: Position, e: Piece?)

    abstract operator fun set(row: Int, column: Int, e: Piece?)

    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int

}