package core

import pieces.Piece
import java.util.AbstractMap

private val SQUARES = 0..7

internal typealias PieceEntry = Map.Entry<Position, Piece>
internal typealias SimplePieceEntry = AbstractMap.SimpleImmutableEntry<Position, Piece>
internal typealias Entry = AbstractMap.SimpleImmutableEntry<Position, Piece?>

internal abstract class Chessboard {

    val rows = SQUARES
    val columns = SQUARES

    abstract fun getPieces(turn: Player): Set<PieceEntry>

    fun forEach(body: (Position, Piece?) -> Unit) {
        for (i in rows) {
            for (j in columns) {
                body(Position(i, j), get(i, j))
            }
        }
    }

    fun filter(check: (Position, Piece?) -> Boolean): Set<Entry> {
        val all: MutableSet<Entry> = mutableSetOf()
        forEach { pos, piece ->
            if (check(pos, piece)) {
                all.add(Entry(pos, piece))
            }
        }
        return all
    }

    fun first(check: (Position, Piece?) -> Boolean): Entry? {
        for (i in rows) {
            for (j in columns) {
                val pos = Position(i, j)
                val piece = get(pos)
                if (check(pos, piece)) {
                    return Entry(pos, piece)
                }
            }
        }
        return null
    }

    fun getAll() = filter { _,_ -> true }

    abstract fun copy(): Chessboard

    abstract operator fun get(row: Int): Array<Piece?>

    abstract operator fun get(p: Position): Piece?

    abstract operator fun get(row: Int, column: Int): Piece?

    abstract operator fun set(p: Position, e: Piece?)

    abstract operator fun set(row: Int, column: Int, e: Piece?)

    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int

}