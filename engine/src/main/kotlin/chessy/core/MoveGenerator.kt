package chessy.core

/**
 * Generates the moves of the side to move.
 *
 * Pseudo-legal moves follow the movement rules of each piece but may leave the own king in check.
 * They are cheaper to generate, and the search discards the illegal ones after [Position.makeMove]
 * with [Position.leftKingInCheck]. Castling is only generated when it is fully legal.
 */
object MoveGenerator {

    private val PROMOTION_TYPES = intArrayOf(QUEEN, KNIGHT, ROOK, BISHOP)

    /**
     * Adds the pseudo-legal moves of the side to move to [moves] (which is cleared first).
     * With [tacticalOnly], only captures and promotions (for the quiescence search).
     */
    fun generatePseudoLegal(position: Position, moves: MoveList, tacticalOnly: Boolean = false) {
        moves.clear()
        val us = position.sideToMove
        val own = position.occupancy(us)
        val enemies = position.occupancy(opposite(us))
        val occupied = own or enemies
        val allowedTargets = if (tacticalOnly) enemies else own.inv()

        generatePawnMoves(position, moves, us, enemies, occupied, tacticalOnly)

        for (type in KNIGHT..KING) {
            position.pieces(us, type).forEachSquare { from ->
                val targets = Attacks.attacks(type, from, occupied) and allowedTargets
                targets.forEachSquare { to ->
                    moves.add(Move(from, to, if (to in enemies) Move.CAPTURE else Move.QUIET))
                }
            }
        }

        if (!tacticalOnly) {
            generateCastling(position, moves, us, occupied)
        }
    }

    /** Adds the legal moves of the side to move to [moves] (which is cleared first). */
    fun generateLegal(position: Position, moves: MoveList) {
        val pseudoLegal = MoveList()
        generatePseudoLegal(position, pseudoLegal)
        moves.clear()
        for (i in 0 until pseudoLegal.size) {
            val move = pseudoLegal[i]
            if (isLegal(position, move)) {
                moves.add(move)
            }
        }
    }

    fun legalMoves(position: Position): MoveList = MoveList().also { generateLegal(position, it) }

    /** Whether a pseudo-legal move does not leave the own king in check. */
    fun isLegal(position: Position, move: Move): Boolean {
        position.makeMove(move)
        val legal = !position.leftKingInCheck()
        position.unmakeMove(move)
        return legal
    }

    private fun generatePawnMoves(position: Position, moves: MoveList, us: Color, enemies: Bitboard, occupied: Bitboard, tacticalOnly: Boolean) {
        val forward = if (us == WHITE) 8 else -8
        val startRank = if (us == WHITE) 1 else 6
        val promotionRank = if (us == WHITE) 7 else 0

        position.pieces(us, PAWN).forEachSquare { from ->
            val oneStep = from + forward
            if (oneStep !in occupied && (!tacticalOnly || rankOf(oneStep) == promotionRank)) {
                addPawnMove(moves, from, oneStep, capture = false)
                val twoSteps = oneStep + forward
                if (!tacticalOnly && rankOf(from) == startRank && twoSteps !in occupied) {
                    moves.add(Move(from, twoSteps, Move.DOUBLE_PAWN_PUSH))
                }
            }

            val attacks = Attacks.pawnAttacks(us, from)
            (attacks and enemies).forEachSquare { to ->
                addPawnMove(moves, from, to, capture = true)
            }

            val enPassant = position.enPassantSquare
            if (enPassant != NO_SQUARE && enPassant in attacks) {
                moves.add(Move(from, enPassant, Move.EN_PASSANT))
            }
        }
    }

    private fun addPawnMove(moves: MoveList, from: Square, to: Square, capture: Boolean) {
        val promotionRank = rankOf(to) == 0 || rankOf(to) == 7
        if (promotionRank) {
            for (type in PROMOTION_TYPES) {
                moves.add(Move(from, to, Move.promotionFlags(type, capture)))
            }
        } else {
            moves.add(Move(from, to, if (capture) Move.CAPTURE else Move.QUIET))
        }
    }

    /** The king cannot castle out of, through or into check, and the squares between king and rook must be empty. */
    private fun generateCastling(position: Position, moves: MoveList, us: Color, occupied: Bitboard) {
        val rights = position.castlingRights
        if (rights and (Castling.kingside(us) or Castling.queenside(us)) == 0) {
            return
        }
        val them = opposite(us)
        // Castling squares of black are the ones of white on the 8th rank
        fun relative(square: Square) = if (us == WHITE) square else flipRank(square)

        val king = relative(E1)
        if (position.isAttacked(king, them)) {
            return
        }

        if (rights and Castling.kingside(us) != 0
            && (bitboardOf(relative(F1)) or bitboardOf(relative(G1))) and occupied == EMPTY
            && !position.isAttacked(relative(F1), them) && !position.isAttacked(relative(G1), them)) {
            moves.add(Move(king, relative(G1), Move.KING_CASTLE))
        }

        if (rights and Castling.queenside(us) != 0
            && (bitboardOf(relative(B1)) or bitboardOf(relative(C1)) or bitboardOf(relative(D1))) and occupied == EMPTY
            && !position.isAttacked(relative(D1), them) && !position.isAttacked(relative(C1), them)) {
            moves.add(Move(king, relative(C1), Move.QUEEN_CASTLE))
        }
    }
}
