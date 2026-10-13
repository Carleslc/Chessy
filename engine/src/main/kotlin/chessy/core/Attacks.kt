package chessy.core

/**
 * Squares attacked by each piece from each square.
 *
 * Knights, kings and pawns use precomputed tables. Sliding pieces (bishops, rooks and queens) use precomputed rays,
 * cut at the first blocker of the ray ("classical approach", https://www.chessprogramming.org/Classical_Approach).
 */
object Attacks {

    /** Ray directions as (file, rank) steps. */
    private enum class Direction(val fileStep: Int, val rankStep: Int) {
        NORTH(0, 1), NORTH_EAST(1, 1), EAST(1, 0), SOUTH_EAST(1, -1),
        SOUTH(0, -1), SOUTH_WEST(-1, -1), WEST(-1, 0), NORTH_WEST(-1, 1);

        /** Whether the square index increases along this direction, so the nearest blocker is the lowest square. */
        val isPositive = rankStep > 0 || (rankStep == 0 && fileStep > 0)
    }

    private val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
    private val KING_STEPS = Direction.values().map { it.fileStep to it.rankStep }

    private val knight = LongArray(64) { stepAttacks(it, KNIGHT_STEPS) }
    private val king = LongArray(64) { stepAttacks(it, KING_STEPS) }
    private val pawn = arrayOf(
        LongArray(64) { stepAttacks(it, listOf(-1 to 1, 1 to 1)) },  // White pawns capture northwards
        LongArray(64) { stepAttacks(it, listOf(-1 to -1, 1 to -1)) } // Black pawns capture southwards
    )

    /** rays[direction][square]: squares from [square] (excluded) to the edge of the board. */
    private val rays = Array(Direction.values().size) { direction ->
        LongArray(64) { square -> ray(square, Direction.values()[direction]) }
    }

    fun knightAttacks(square: Square): Bitboard = knight[square]

    fun kingAttacks(square: Square): Bitboard = king[square]

    /** Squares attacked by a pawn of [color] on [square] (only diagonal captures, not pushes). */
    fun pawnAttacks(color: Color, square: Square): Bitboard = pawn[color][square]

    fun bishopAttacks(square: Square, occupied: Bitboard): Bitboard =
        rayAttacks(square, occupied, Direction.NORTH_EAST) or rayAttacks(square, occupied, Direction.SOUTH_EAST) or
        rayAttacks(square, occupied, Direction.SOUTH_WEST) or rayAttacks(square, occupied, Direction.NORTH_WEST)

    fun rookAttacks(square: Square, occupied: Bitboard): Bitboard =
        rayAttacks(square, occupied, Direction.NORTH) or rayAttacks(square, occupied, Direction.EAST) or
        rayAttacks(square, occupied, Direction.SOUTH) or rayAttacks(square, occupied, Direction.WEST)

    fun queenAttacks(square: Square, occupied: Bitboard): Bitboard = bishopAttacks(square, occupied) or rookAttacks(square, occupied)

    /** Squares attacked by a piece of [type] (not a pawn) on [square], given the [occupied] squares. */
    fun attacks(type: PieceType, square: Square, occupied: Bitboard): Bitboard = when (type) {
        KNIGHT -> knightAttacks(square)
        BISHOP -> bishopAttacks(square, occupied)
        ROOK -> rookAttacks(square, occupied)
        QUEEN -> queenAttacks(square, occupied)
        KING -> kingAttacks(square)
        else -> throw IllegalArgumentException("Pawn attacks depend on the color, use pawnAttacks")
    }

    /** The ray from [square] in [direction] until (and including) the first occupied square. */
    private fun rayAttacks(square: Square, occupied: Bitboard, direction: Direction): Bitboard {
        val ray = rays[direction.ordinal][square]
        val blockers = ray and occupied
        if (blockers == EMPTY) {
            return ray
        }
        val firstBlocker = if (direction.isPositive) lsb(blockers) else msb(blockers)
        // Remove the squares behind the first blocker
        return ray xor rays[direction.ordinal][firstBlocker]
    }

    private fun stepAttacks(square: Square, steps: List<Pair<Int, Int>>): Bitboard {
        var attacks = EMPTY
        for ((fileStep, rankStep) in steps) {
            val file = fileOf(square) + fileStep
            val rank = rankOf(square) + rankStep
            if (file in 0..7 && rank in 0..7) {
                attacks = attacks or bitboardOf(square(file, rank))
            }
        }
        return attacks
    }

    private fun ray(square: Square, direction: Direction): Bitboard {
        var ray = EMPTY
        var file = fileOf(square) + direction.fileStep
        var rank = rankOf(square) + direction.rankStep
        while (file in 0..7 && rank in 0..7) {
            ray = ray or bitboardOf(square(file, rank))
            file += direction.fileStep
            rank += direction.rankStep
        }
        return ray
    }
}
