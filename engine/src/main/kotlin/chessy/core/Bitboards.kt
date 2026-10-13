package chessy.core

/**
 * A set of squares as a 64-bit integer: bit `n` is set when square `n` belongs to the set.
 * See https://www.chessprogramming.org/Bitboards
 */
typealias Bitboard = Long

const val EMPTY: Bitboard = 0L

const val FILE_A: Bitboard = 0x0101010101010101L
const val FILE_H: Bitboard = FILE_A shl 7
const val RANK_1: Bitboard = 0xFFL
const val RANK_8: Bitboard = RANK_1 shl 56

/** b1, a2, d1... (a1 is a dark square). */
const val LIGHT_SQUARES: Bitboard = 0x55AA55AA55AA55AAL

fun fileMask(file: Int): Bitboard = FILE_A shl file

fun rankMask(rank: Int): Bitboard = RANK_1 shl (8 * rank)

fun bitboardOf(square: Square): Bitboard = 1L shl square

fun bitboardOf(vararg squares: Square): Bitboard = squares.fold(EMPTY) { bitboard, square -> bitboard or bitboardOf(square) }

operator fun Bitboard.contains(square: Square): Boolean = (this ushr square) and 1L != 0L

fun popCount(bitboard: Bitboard): Int = java.lang.Long.bitCount(bitboard)

/** Lowest square of a non-empty bitboard. */
fun lsb(bitboard: Bitboard): Square = java.lang.Long.numberOfTrailingZeros(bitboard)

/** Highest square of a non-empty bitboard. */
fun msb(bitboard: Bitboard): Square = 63 - java.lang.Long.numberOfLeadingZeros(bitboard)

/** Calls [action] for every square of the bitboard, from a1 to h8. */
inline fun Bitboard.forEachSquare(action: (Square) -> Unit) {
    var remaining = this
    while (remaining != EMPTY) {
        action(lsb(remaining))
        remaining = remaining and (remaining - 1) // Clears the lowest square
    }
}

/** Board diagram of the bitboard, rank 8 on top (`x` for squares in the set). */
fun bitboardToString(bitboard: Bitboard): String = buildString {
    for (rank in 7 downTo 0) {
        append(rank + 1).append(' ')
        for (file in 0..7) {
            append(if (square(file, rank) in bitboard) " x" else " .")
        }
        append('\n')
    }
    append("   a b c d e f g h")
}
