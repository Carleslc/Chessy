package core

import pieces.Piece

internal class ChessboardMap(private val whitePieces: MutableMap<Position, Piece>,
                             private val blackPieces: MutableMap<Position, Piece>) : Chessboard() {

    companion object Factory {

        fun empty() = ChessboardMap(hashMapOf(), hashMapOf())

        fun copy(chessboard: ChessboardMap) = ChessboardMap(HashMap(chessboard.whitePieces), HashMap(chessboard.blackPieces))

    }

    private fun getPiecesMap(player: Player) = if (player == Player.WHITE) whitePieces else blackPieces

    override fun getPieces(turn: Player): Set<Map.Entry<Position, Piece>> = getPiecesMap(turn).entries

    override fun copy() = Factory.copy(this)

    override operator fun get(row: Int): Array<Piece?> {
        val rowArray: Array<Piece?> = arrayOfNulls(8)
        for (column in columns) {
            rowArray[column] = get(row, column)
        }
        return rowArray
    }

    override operator fun get(p: Position): Piece? = whitePieces[p] ?: blackPieces[p]

    override operator fun get(row: Int, column: Int): Piece? = get(Position(row, column))

    override operator fun set(p: Position, e: Piece?) {
        whitePieces.remove(p) ?: blackPieces.remove(p)
        if (e != null) {
            getPiecesMap(e.player)[p] = e
        }
    }

    override operator fun set(row: Int, column: Int, e: Piece?) {
        set(Position(row, column), e)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChessboardMap

        if (whitePieces != other.whitePieces) return false
        if (blackPieces != other.blackPieces) return false

        return true
    }

    override fun hashCode(): Int = 31 * whitePieces.hashCode() + blackPieces.hashCode()

}