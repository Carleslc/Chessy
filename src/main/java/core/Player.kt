package core

enum class Player {
    WHITE, BLACK;

    fun opponent(): Player = if (this == WHITE) BLACK else WHITE

    fun isChessy(): Boolean = this == CHESSY

    fun isPlayer(): Boolean = this == PLAYER

    fun getName() = if (isChessy()) "CHESSY" else "PLAYER"

    override fun toString() = if (this == WHITE) "\u25A1" else "\u25A0"
}