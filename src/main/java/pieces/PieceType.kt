package pieces

enum class PieceType(val notation: String, val weight: Int, val preference: Int) {
    PAWN    ("P", 100, 4),
    KNIGHT  ("N", 325, 5),
    BISHOP  ("B", 350, 5),
    ROOK    ("R", 500, 2),
    QUEEN   ("Q", 900, 3),
    KING    ("K", 32767, 1);
}