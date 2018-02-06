package core

enum class Status {
    PLAYING,
    CHECK,
    CHECKMATE,
    STALEMATE,
    NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES;

    fun isTerminal() = this != PLAYING && this != CHECK

    fun getDescription(player: Player): String {
        return when (this) {
            Status.PLAYING -> "Playing"
            Status.CHECK -> "${player.getName()} is in check!"
            Status.CHECKMATE -> "${player.opponent().getName()} WON!"
            Status.STALEMATE -> "${player.getName()} is in stalemate! It's a DRAW!"
            Status.NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES -> "In fifty moves there has been no capture or pawn movement. It's a DRAW!"
        }
    }
}