package core

/** Castling rights. Row 7 is the white back rank and row 0 the black one. */
enum class Castling(val player: Player, val kingside: Boolean, val notation: Char) {
    WHITE_KINGSIDE(Player.WHITE, true, 'K'),
    WHITE_QUEENSIDE(Player.WHITE, false, 'Q'),
    BLACK_KINGSIDE(Player.BLACK, true, 'k'),
    BLACK_QUEENSIDE(Player.BLACK, false, 'q');

    private val row = if (player == Player.WHITE) 7 else 0

    val kingFrom = Position(row, 4)
    val kingTo = Position(row, if (kingside) 6 else 2)
    val rookFrom = Position(row, if (kingside) 7 else 0)
    val rookTo = Position(row, if (kingside) 5 else 3)

    /** Squares between the king and the rook, which must be empty. */
    val emptySquares = (if (kingside) 5..6 else 1..3).map { Position(row, it) }

    /** Squares where the king starts, passes through and ends, which cannot be attacked. */
    val kingSquares = (if (kingside) 4..6 else 2..4).map { Position(row, it) }

    /** Standard algebraic notation. */
    val san = if (kingside) "O-O" else "O-O-O"
}
