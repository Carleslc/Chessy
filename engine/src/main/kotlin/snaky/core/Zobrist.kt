package snaky.core

/**
 * Random keys to hash positions (https://www.chessprogramming.org/Zobrist_Hashing).
 * The hash of a position is the XOR of the keys of its features, so it can be updated incrementally on each move.
 * Keys are generated with a fixed seed, so hashes are the same on every run.
 */
object Zobrist {

    private var seed = 0x2C3E1A5B7D9F4E61L

    /** xorshift64* pseudo-random generator. */
    private fun nextKey(): Long {
        seed = seed xor (seed ushr 12)
        seed = seed xor (seed shl 25)
        seed = seed xor (seed ushr 27)
        return seed * 0x2545F4914F6CDD1DL
    }

    /** pieceSquare[piece][square] */
    private val pieceSquare = Array(PIECES) { LongArray(64) { nextKey() } }

    /** castling[rights] for each of the 16 combinations of castling rights. */
    private val castling = LongArray(16) { nextKey() }

    /** enPassant[file] when an en passant capture is available on that file. */
    private val enPassant = LongArray(8) { nextKey() }

    val blackToMove: Long = nextKey()

    fun piece(piece: Piece, square: Square): Long = pieceSquare[piece][square]

    fun castling(rights: Int): Long = castling[rights]

    fun enPassant(square: Square): Long = enPassant[fileOf(square)]
}
