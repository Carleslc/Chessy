package core

enum class Player {
    WHITE, BLACK;

    fun opponent(): Player = if (this == WHITE) BLACK else WHITE

    fun isChessy(): Boolean = this == CHESSY

    fun isPlayer(): Boolean = this != CHESSY
}