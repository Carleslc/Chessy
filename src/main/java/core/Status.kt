package core

/** Status of the player in turn. */
enum class Status {
    PLAYING,
    CHECK,
    CHECKMATE,
    STALEMATE,
    NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES,
    INSUFFICIENT_MATERIAL,
    THREEFOLD_REPETITION;

    fun isTerminal() = this != PLAYING && this != CHECK

    fun isDraw() = isTerminal() && this != CHECKMATE

    fun isCheck() = this == CHECK || this == CHECKMATE

    fun getDescription(player: Player): String {
        return when (this) {
            PLAYING -> "${player.getName()} to move"
            CHECK -> "${player.getName()} is in check!"
            CHECKMATE -> "Checkmate! ${player.opponent().getName()} WON!"
            STALEMATE -> "${player.getName()} is in stalemate! It's a DRAW!"
            NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES -> "In fifty moves there has been no capture or pawn movement. It's a DRAW!"
            INSUFFICIENT_MATERIAL -> "Insufficient material to checkmate. It's a DRAW!"
            THREEFOLD_REPETITION -> "The same position has been repeated three times. It's a DRAW!"
        }
    }
}
