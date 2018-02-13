package core

import core.Player.Declarations.CHESSY
import core.Player.Declarations.PLAYER

enum class Player(val notation: Char) {
    WHITE ('\u25A1'),
    BLACK ('\u25A0');

    object Declarations {
        val START = Player.WHITE

        val CHESSY = Player.WHITE
        val PLAYER = CHESSY.opponent()
    }

    fun opponent(): Player = if (this == WHITE) BLACK else WHITE

    fun isChessy(): Boolean = this == CHESSY

    fun isPlayer(): Boolean = this == PLAYER

    fun getName() = if (isChessy()) "CHESSY" else "PLAYER"

    override fun toString() = notation.toString()
}