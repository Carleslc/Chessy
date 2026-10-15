package chessy.book

import chessy.core.BLACK
import chessy.core.Castling
import chessy.core.KING
import chessy.core.Move
import chessy.core.MoveGenerator
import chessy.core.NO_SQUARE
import chessy.core.PAWN
import chessy.core.Position
import chessy.core.WHITE
import chessy.core.colorOf
import chessy.core.fileOf
import chessy.core.forEachSquare
import chessy.core.rankOf
import chessy.core.typeOf
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Opening book in the Polyglot format (https://chessprogramming.org/PolyGlot): entries of 16 bytes with the key of a
 * position, a move and its weight, sorted by key. The move to play is chosen at random in proportion to the weights,
 * so the openings vary from game to game.
 *
 * The books of Chessy are built with `tools/book.py` from a repertoire of openings where one side gains space.
 */
class OpeningBook(bytes: ByteArray) {

    private val keys: LongArray
    private val moves: IntArray
    private val weights: IntArray

    init {
        require(bytes.size % ENTRY_SIZE == 0) { "Invalid Polyglot book (its size is not a multiple of $ENTRY_SIZE bytes)" }
        val buffer = ByteBuffer.wrap(bytes) // Big endian, as Polyglot
        val entries = bytes.size / ENTRY_SIZE
        keys = LongArray(entries)
        moves = IntArray(entries)
        weights = IntArray(entries)
        for (i in 0 until entries) {
            keys[i] = buffer.getLong()
            moves[i] = buffer.getShort().toInt() and 0xFFFF
            weights[i] = buffer.getShort().toInt() and 0xFFFF
            buffer.getInt() // Learning data, not used
        }
    }

    val size: Int get() = keys.size

    /** Legal moves of the book for the [position], with their weights. */
    fun moves(position: Position): List<Pair<Move, Int>> {
        val key = key(position)
        var index = firstIndex(key)
        if (index < 0) return emptyList()
        val legalMoves = MoveGenerator.legalMoves(position).toList()
        val result = mutableListOf<Pair<Move, Int>>()
        while (index < keys.size && keys[index] == key) {
            val move = legalMoves.firstOrNull { polyglotMove(position, it) == moves[index] }
            if (move != null && weights[index] > 0) result.add(move to weights[index])
            index++
        }
        return result
    }

    /** A move of the book for the [position], chosen at random in proportion to the weights, or null if there is none. */
    fun pick(position: Position, random: Random = Random.Default): Move? {
        val candidates = moves(position)
        if (candidates.isEmpty()) return null
        var choice = random.nextInt(candidates.sumOf { it.second })
        for ((move, weight) in candidates) {
            choice -= weight
            if (choice < 0) return move
        }
        return candidates.last().first
    }

    /** First entry with the [key] (binary search), or -1. */
    private fun firstIndex(key: Long): Int {
        var low = 0
        var high = keys.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            // Keys are unsigned 64-bit numbers
            val comparison = java.lang.Long.compareUnsigned(keys[middle], key)
            if (comparison < 0) low = middle + 1
            else {
                if (comparison == 0) found = middle
                high = middle - 1
            }
        }
        return found
    }

    companion object {
        private const val ENTRY_SIZE = 16

        /** Polyglot random numbers: 768 for pieces on squares, 4 castling rights, 8 en passant files and the turn. */
        private val RANDOM: LongArray = OpeningBook::class.java.getResource("/chessy/book/polyglot.random")!!.readText()
            .lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { java.lang.Long.parseUnsignedLong(it.trim(), 16) }
            .toList().toLongArray().also { require(it.size == 781) }

        private const val CASTLING_OFFSET = 768
        private const val EN_PASSANT_OFFSET = 772
        private const val TURN_OFFSET = 780

        /** Polyglot key of the [position]. */
        fun key(position: Position): Long {
            var key = 0L
            position.occupied.forEachSquare { square ->
                val piece = position.pieceOn(square)
                // Kinds: black pawn 0, white pawn 1, black knight 2, ..., white king 11
                val kind = 2 * typeOf(piece) + if (colorOf(piece) == WHITE) 1 else 0
                key = key xor RANDOM[64 * kind + square]
            }
            val castling = intArrayOf(Castling.WHITE_KINGSIDE, Castling.WHITE_QUEENSIDE, Castling.BLACK_KINGSIDE, Castling.BLACK_QUEENSIDE)
            for ((i, right) in castling.withIndex()) {
                if (position.castlingRights and right != 0) key = key xor RANDOM[CASTLING_OFFSET + i]
            }
            // Positions only keep the en passant square when a pawn can capture, as Polyglot requires
            if (position.enPassantSquare != NO_SQUARE) key = key xor RANDOM[EN_PASSANT_OFFSET + fileOf(position.enPassantSquare)]
            if (position.sideToMove == WHITE) key = key xor RANDOM[TURN_OFFSET]
            return key
        }

        /** Polyglot encoding of a [move]: to file and rank, from file and rank, promotion; castling as the king taking its rook. */
        fun polyglotMove(position: Position, move: Move): Int {
            val promotion = if (move.isPromotion) move.promotionType - PAWN else 0 // Knight 1, bishop 2, rook 3, queen 4
            return fileOf(move.to) or (rankOf(move.to) shl 3) or (fileOf(move.from) shl 6) or (rankOf(move.from) shl 9) or (promotion shl 12)
        }

        fun load(file: File): OpeningBook = OpeningBook(file.readBytes())

        /** Books of Chessy (resources): "book" with every opening, or one opening of [openings]. */
        fun resource(name: String): OpeningBook =
            OpeningBook(OpeningBook::class.java.getResource("/chessy/book/$name.bin")?.readBytes() ?: throw IllegalArgumentException("Unknown book: $name"))

        /** Openings of the books of Chessy, to play only one of them. */
        val openings: List<String> by lazy {
            OpeningBook::class.java.getResource("/chessy/book/openings.txt")!!.readText().lines().filter { it.isNotBlank() }
        }
    }
}
