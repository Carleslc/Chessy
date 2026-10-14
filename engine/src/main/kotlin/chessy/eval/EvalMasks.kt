package chessy.eval

import chessy.core.Attacks
import chessy.core.BLACK
import chessy.core.Bitboard
import chessy.core.Color
import chessy.core.EMPTY
import chessy.core.FILE_A
import chessy.core.FILE_H
import chessy.core.Square
import chessy.core.WHITE
import chessy.core.bitboardOf
import chessy.core.fileMask
import chessy.core.fileOf
import chessy.core.rankMask
import chessy.core.rankOf

/** Precomputed sets of squares used by the evaluation. */
object EvalMasks {

    /** Files next to each file. */
    val ADJACENT_FILES = LongArray(8) { file ->
        (if (file > 0) fileMask(file - 1) else EMPTY) or (if (file < 7) fileMask(file + 1) else EMPTY)
    }

    /** forwardRanks[color][rank]: ranks in front of [rank] from the point of view of [color]. */
    private val forwardRanks = Array(2) { color ->
        LongArray(8) { rank ->
            var mask = EMPTY
            for (r in 0..7) {
                if ((color == WHITE && r > rank) || (color == BLACK && r < rank)) mask = mask or rankMask(r)
            }
            mask
        }
    }

    /** Squares in front of a square on the same file (where a pawn advances). */
    val FORWARD_FILE = Array(2) { color -> LongArray(64) { sq -> forwardRanks[color][rankOf(sq)] and fileMask(fileOf(sq)) } }

    /** Squares in front of a square on the adjacent files (squares that pawns there could attack in the future). */
    val PAWN_ATTACK_SPAN = Array(2) { color -> LongArray(64) { sq -> forwardRanks[color][rankOf(sq)] and ADJACENT_FILES[fileOf(sq)] } }

    /** A pawn is passed when there are no enemy pawns in this mask: in front of it, on its file or the adjacent ones. */
    val PASSED_PAWN = Array(2) { color -> LongArray(64) { sq -> FORWARD_FILE[color][sq] or PAWN_ATTACK_SPAN[color][sq] } }

    /** Squares around the king, including its own square. */
    val KING_ZONE = LongArray(64) { sq -> Attacks.kingAttacks(sq) or bitboardOf(sq) }

    /** Central files c, d, e and f. */
    val CENTER_FILES: Bitboard = fileMask(2) or fileMask(3) or fileMask(4) or fileMask(5)

    /** Ranks 2 to 4 of each color: its space area together with [CENTER_FILES]. */
    val SPACE_RANKS = arrayOf(rankMask(1) or rankMask(2) or rankMask(3), rankMask(4) or rankMask(5) or rankMask(6))

    /** Half of the board of the opponent (ranks 5 to 8 for white). */
    val ENEMY_HALF = arrayOf(rankMask(4) or rankMask(5) or rankMask(6) or rankMask(7), rankMask(0) or rankMask(1) or rankMask(2) or rankMask(3))

    /** Ranks 4 to 6 of each color, where outposts are useful. */
    val OUTPOST_RANKS = arrayOf(rankMask(3) or rankMask(4) or rankMask(5), rankMask(2) or rankMask(3) or rankMask(4))

    /** Squares attacked by all the [pawns] of [color]. */
    fun pawnAttacks(color: Color, pawns: Bitboard): Bitboard =
        if (color == WHITE) ((pawns and FILE_A.inv()) shl 7) or ((pawns and FILE_H.inv()) shl 9)
        else ((pawns and FILE_A.inv()) ushr 9) or ((pawns and FILE_H.inv()) ushr 7)

    /** Squares attacked by two pawns of [color]. */
    fun pawnDoubleAttacks(color: Color, pawns: Bitboard): Bitboard =
        if (color == WHITE) ((pawns and FILE_A.inv()) shl 7) and ((pawns and FILE_H.inv()) shl 9)
        else ((pawns and FILE_A.inv()) ushr 9) and ((pawns and FILE_H.inv()) ushr 7)

    /** One square forward for [color]. */
    fun forward(color: Color, bitboard: Bitboard): Bitboard = if (color == WHITE) bitboard shl 8 else bitboard ushr 8

    /** One square backward for [color]. */
    fun backward(color: Color, bitboard: Bitboard): Bitboard = if (color == WHITE) bitboard ushr 8 else bitboard shl 8

    /** Number of king moves between two squares. */
    fun distance(a: Square, b: Square): Int = maxOf(Math.abs(fileOf(a) - fileOf(b)), Math.abs(rankOf(a) - rankOf(b)))
}
