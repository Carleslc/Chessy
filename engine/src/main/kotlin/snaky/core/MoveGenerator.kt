package snaky.core

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

    /**
     * Castling, also in Chess960: the king goes to the g file (kingside) or c file (queenside) and its rook next to it,
     * to the f or d file. Every square that king and rook cross must be empty (but for themselves), and the king
     * cannot castle out of, through or into check. It is encoded as the king moving to the square of its rook.
     */
    private fun generateCastling(position: Position, moves: MoveList, us: Color, occupied: Bitboard) {
        val rights = position.castlingRights and (Castling.kingside(us) or Castling.queenside(us))
        if (rights == 0) return
        val them = opposite(us)
        val king = position.kingSquare(us)

        for (right in intArrayOf(Castling.kingside(us), Castling.queenside(us))) {
            if (rights and right == 0) continue
            val rook = position.castlingRook(right)
            val kingside = right == Castling.kingside(us)
            val kingTo = square(if (kingside) 6 else 2, rankOf(king))
            val rookTo = square(if (kingside) 5 else 3, rankOf(king))
            val castlers = bitboardOf(king) or bitboardOf(rook)
            if ((rankSegment(king, kingTo) or rankSegment(rook, rookTo)) and castlers.inv() and occupied != EMPTY) continue
            // Attacks through the squares of the king and the rook, which leave them
            val others = occupied and castlers.inv()
            var safe = true
            rankSegment(king, kingTo).forEachSquare { square ->
                if (position.attackersTo(square, others) and position.occupancy(them) != EMPTY) safe = false
            }
            if (safe) moves.add(Move(king, rook, if (kingside) Move.KING_CASTLE else Move.QUEEN_CASTLE))
        }
    }

    /** Squares of the rank of [a] from the file of [a] to the file of [b], both included. */
    private fun rankSegment(a: Square, b: Square): Bitboard {
        var segment = EMPTY
        for (file in minOf(fileOf(a), fileOf(b))..maxOf(fileOf(a), fileOf(b))) segment = segment or bitboardOf(square(file, rankOf(a)))
        return segment
    }
}
