package pieces

enum class PieceType(val weight: Int, val notation: String) {
    KING    (32767, "K"),
    QUEEN   (1100, "Q"),
    ROOK    (500, "R"),
    BISHOP  (320, "B"),
    KNIGHT  (320, "N"),
    PAWN    (100, "P");
}