package snaky.core

/**
 * Static exchange evaluation (SEE, https://www.chessprogramming.org/Static_Exchange_Evaluation):
 * the material won or lost by a capture after all the recaptures on the same square, each side capturing with
 * its least valuable attacker and stopping when continuing would lose material. It considers the attackers and
 * defenders of the square, including the pieces behind them (x-rays), but not pins nor checks.
 */
object StaticExchange {

    /** Piece values in centipawns, indexed by piece type. */
    val VALUES = intArrayOf(100, 320, 330, 500, 900, 20_000)

    /** Material balance of the [move] for the side that plays it (0 for quiet moves without recaptures). */
    fun evaluate(position: Position, move: Move): Int {
        val from = move.from
        val to = move.to
        val gains = IntArray(32)

        var occupied = position.occupied xor bitboardOf(from)
        gains[0] = if (move.isEnPassant) {
            occupied = occupied xor bitboardOf(if (position.sideToMove == WHITE) to - 8 else to + 8)
            VALUES[PAWN]
        } else {
            val captured = position.pieceOn(to)
            if (captured == NO_PIECE) 0 else VALUES[typeOf(captured)]
        }

        // Value of the piece standing on the target square, which can be captured next
        var onSquare = if (move.isPromotion) VALUES[move.promotionType] else VALUES[typeOf(position.pieceOn(from))]
        if (move.isPromotion) gains[0] += VALUES[move.promotionType] - VALUES[PAWN]

        var side = opposite(position.sideToMove)
        var depth = 0

        while (true) {
            val attackers = position.attackersTo(to, occupied) and occupied and position.occupancy(side)
            if (attackers == EMPTY) break
            val attacker = leastValuableAttacker(position, attackers, side)

            depth++
            // Score if the side captures the piece on the square (and it is not recaptured)
            gains[depth] = onSquare - gains[depth - 1]

            occupied = occupied xor bitboardOf(attacker)
            onSquare = VALUES[typeOf(position.pieceOn(attacker))]
            side = opposite(side)
        }

        // Each side can stop capturing when it is not favorable
        while (depth > 0) {
            gains[depth - 1] = -maxOf(-gains[depth - 1], gains[depth])
            depth--
        }
        return gains[0]
    }

    /** Whether the [move] does not lose material ([evaluate] >= [threshold]). */
    fun isAtLeast(position: Position, move: Move, threshold: Int = 0): Boolean = evaluate(position, move) >= threshold

    private fun leastValuableAttacker(position: Position, attackers: Bitboard, side: Color): Square {
        for (type in PAWN..KING) {
            val pieces = attackers and position.pieces(side, type)
            if (pieces != EMPTY) return lsb(pieces)
        }
        throw IllegalStateException("No attacker")
    }
}
