package core

data class Move(val from: Position, val to: Position, private val currentBoard: Board) {

    val nextBoard: Board by lazy {
        currentBoard.copy().apply(this)
    }

    override fun toString(): String {
        return "$from -> $to"
    }

}