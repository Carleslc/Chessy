package chessy.core

/*
 * Basic chess types encoded as plain integers, so they can index arrays and live in the hot path without allocations.
 * Type aliases only document the meaning of each Int.
 */

/** Square index 0..63 in little-endian rank-file order: a1 = 0, b1 = 1, ..., h1 = 7, a2 = 8, ..., h8 = 63. */
typealias Square = Int

/** [WHITE] or [BLACK]. */
typealias Color = Int

/** [PAWN], [KNIGHT], [BISHOP], [ROOK], [QUEEN] or [KING]. */
typealias PieceType = Int

/** A piece type of a color: `color * 6 + type`, or [NO_PIECE]. */
typealias Piece = Int

const val WHITE: Color = 0
const val BLACK: Color = 1

const val PAWN: PieceType = 0
const val KNIGHT: PieceType = 1
const val BISHOP: PieceType = 2
const val ROOK: PieceType = 3
const val QUEEN: PieceType = 4
const val KING: PieceType = 5

const val PIECE_TYPES = 6
const val PIECES = 12
const val NO_PIECE: Piece = -1
const val NO_SQUARE: Square = -1

fun opposite(color: Color): Color = color xor 1

fun piece(color: Color, type: PieceType): Piece = color * PIECE_TYPES + type

fun colorOf(piece: Piece): Color = piece / PIECE_TYPES

fun typeOf(piece: Piece): PieceType = piece % PIECE_TYPES

fun fileOf(square: Square): Int = square and 7

fun rankOf(square: Square): Int = square shr 3

fun square(file: Int, rank: Int): Square = rank * 8 + file

/** Same square seen from the black side (a1 <-> a8). */
fun flipRank(square: Square): Square = square xor 56

/** Rank from the point of view of [color]: the own back rank is 0 and the promotion rank is 7. */
fun relativeRank(color: Color, square: Square): Int = if (color == WHITE) rankOf(square) else 7 - rankOf(square)

fun squareName(square: Square): String = "${'a' + fileOf(square)}${'1' + rankOf(square)}"

/** Parses a square in algebraic notation, such as `e4`. */
fun parseSquare(name: String): Square {
    require(name.length == 2 && name[0] in 'a'..'h' && name[1] in '1'..'8') { "Invalid square: $name" }
    return square(name[0] - 'a', name[1] - '1')
}

private const val PIECE_CHARS = "PNBRQKpnbrqk"

/** FEN letter of a piece: uppercase for white, lowercase for black. */
fun pieceChar(piece: Piece): Char = if (piece == NO_PIECE) '.' else PIECE_CHARS[piece]

fun parsePiece(char: Char): Piece {
    val piece = PIECE_CHARS.indexOf(char)
    require(piece >= 0) { "Invalid piece: $char" }
    return piece
}

// Squares used by castling
const val A1: Square = 0
const val B1: Square = 1
const val C1: Square = 2
const val D1: Square = 3
const val E1: Square = 4
const val F1: Square = 5
const val G1: Square = 6
const val H1: Square = 7
const val A8: Square = 56
const val B8: Square = 57
const val C8: Square = 58
const val D8: Square = 59
const val E8: Square = 60
const val F8: Square = 61
const val G8: Square = 62
const val H8: Square = 63
