package chessy.core

/**
 * A move encoded in 16 bits (https://www.chessprogramming.org/Encoding_Moves):
 *
 * - bits 0-5: origin square
 * - bits 6-11: target square
 * - bits 12-15: flags ([QUIET], [DOUBLE_PAWN_PUSH], castling, [CAPTURE], [EN_PASSANT] and promotions)
 *
 * It is an inline class: at runtime it is a plain Int, without allocations.
 */
@JvmInline
value class Move(val bits: Int) {

    constructor(from: Square, to: Square, flags: Int = QUIET) : this(from or (to shl 6) or (flags shl 12))

    val from: Square get() = bits and 0x3F
    val to: Square get() = (bits shr 6) and 0x3F
    val flags: Int get() = bits shr 12

    val isCapture: Boolean get() = flags and CAPTURE != 0
    val isPromotion: Boolean get() = flags and PROMOTION != 0
    val isEnPassant: Boolean get() = flags == EN_PASSANT
    val isDoublePawnPush: Boolean get() = flags == DOUBLE_PAWN_PUSH
    val isCastling: Boolean get() = flags == KING_CASTLE || flags == QUEEN_CASTLE

    /** Piece type to promote to ([KNIGHT], [BISHOP], [ROOK] or [QUEEN]). Only valid for promotions. */
    val promotionType: PieceType get() = KNIGHT + (flags and 3)

    /** UCI notation: `e2e4`, `e1g1` (castling), `e7e8q` (promotion). */
    override fun toString(): String {
        if (this == NONE) return "0000"
        val promotion = if (isPromotion) "nbrq"[promotionType - KNIGHT].toString() else ""
        return squareName(from) + squareName(to) + promotion
    }

    companion object {
        const val QUIET = 0
        const val DOUBLE_PAWN_PUSH = 1
        const val KING_CASTLE = 2
        const val QUEEN_CASTLE = 3
        const val CAPTURE = 4
        const val EN_PASSANT = 5
        /** Promotion flag, combined with the promotion type (0 = knight ... 3 = queen) and [CAPTURE]. */
        const val PROMOTION = 8

        fun promotionFlags(type: PieceType, capture: Boolean): Int = PROMOTION or (type - KNIGHT) or (if (capture) CAPTURE else 0)

        /** No move (a1a1 is never a valid move). */
        val NONE = Move(0)
    }
}

/** Fixed-size list of moves without allocations, to be reused at each ply of the search. */
class MoveList(capacity: Int = MAX_MOVES) {

    private val moves = IntArray(capacity)

    var size = 0
        private set

    fun add(move: Move) {
        moves[size++] = move.bits
    }

    operator fun get(index: Int): Move = Move(moves[index])

    fun clear() {
        size = 0
    }

    fun isEmpty() = size == 0

    operator fun contains(move: Move): Boolean = (0 until size).any { moves[it] == move.bits }

    fun toList(): List<Move> = List(size) { get(it) }

    override fun toString(): String = toList().joinToString(" ")

    companion object {
        /** Maximum number of legal moves in a chess position is 218. */
        const val MAX_MOVES = 256
    }
}
