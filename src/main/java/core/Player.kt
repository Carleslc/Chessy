package core

enum class Player(val notation: Char) {
    WHITE ('□'),
    BLACK ('■');

    object Declarations {
        val START = Player.WHITE

        /** Players controlled by Chessy (the other ones are human players). */
        val CHESSY = mutableSetOf(Player.WHITE)
    }

    fun opponent(): Player = if (this == WHITE) BLACK else WHITE

    fun isChessy(): Boolean = this in Declarations.CHESSY

    fun isPlayer(): Boolean = !isChessy()

    fun getName() = (if (isChessy()) "CHESSY" else "PLAYER") + " ($notation)"

    override fun toString() = notation.toString()
}
