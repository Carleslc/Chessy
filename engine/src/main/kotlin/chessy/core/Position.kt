package chessy.core

/** Castling rights, as bit flags. */
object Castling {
    const val WHITE_KINGSIDE = 1
    const val WHITE_QUEENSIDE = 2
    const val BLACK_KINGSIDE = 4
    const val BLACK_QUEENSIDE = 8
    const val NONE = 0
    const val ALL = 15

    fun kingside(color: Color) = if (color == WHITE) WHITE_KINGSIDE else BLACK_KINGSIDE
    fun queenside(color: Color) = if (color == WHITE) WHITE_QUEENSIDE else BLACK_QUEENSIDE

    /** Rights kept when a piece moves from or to each square (moving the king or a rook, or capturing a rook). */
    internal val keptRights = IntArray(64) { ALL }.apply {
        this[E1] = ALL and (WHITE_KINGSIDE or WHITE_QUEENSIDE).inv()
        this[H1] = ALL and WHITE_KINGSIDE.inv()
        this[A1] = ALL and WHITE_QUEENSIDE.inv()
        this[E8] = ALL and (BLACK_KINGSIDE or BLACK_QUEENSIDE).inv()
        this[H8] = ALL and BLACK_KINGSIDE.inv()
        this[A8] = ALL and BLACK_QUEENSIDE.inv()
    }
}

/**
 * A chess position, represented with bitboards (one per piece) and a mailbox (piece on each square).
 *
 * Moves are played with [makeMove] and taken back with [unmakeMove] on the same instance, without copying,
 * so a single position is reused during the whole search.
 */
class Position private constructor() {

    /** Squares of each piece, indexed by [Piece]. */
    private val pieceBitboards = LongArray(PIECES)

    /** Squares occupied by each color. */
    private val colorBitboards = LongArray(2)

    /** Piece on each square, or [NO_PIECE]. */
    private val board = IntArray(64) { NO_PIECE }

    var sideToMove: Color = WHITE
        private set

    /** Bit flags of [Castling]. */
    var castlingRights: Int = Castling.NONE
        private set

    /** Square where an en passant capture is possible, or [NO_SQUARE]. */
    var enPassantSquare: Square = NO_SQUARE
        private set

    /** Half moves since the last capture or pawn move, for the fifty-move rule. */
    var halfmoveClock: Int = 0
        private set

    var fullmoveNumber: Int = 1
        private set

    /** Zobrist hash of the position, updated incrementally. */
    var key: Long = 0L
        private set

    /** State to restore on [unmakeMove], one entry per move played. */
    private val history = Array(MAX_HISTORY) { State() }

    /** Number of moves played on this position (since it was created from a FEN). */
    var historySize = 0
        private set

    private class State {
        var move = Move.NONE
        var captured = NO_PIECE
        var castlingRights = Castling.NONE
        var enPassantSquare = NO_SQUARE
        var halfmoveClock = 0
        var key = 0L
    }

    // ---- Queries ----

    fun pieceOn(square: Square): Piece = board[square]

    fun pieces(piece: Piece): Bitboard = pieceBitboards[piece]

    fun pieces(color: Color, type: PieceType): Bitboard = pieceBitboards[piece(color, type)]

    fun occupancy(color: Color): Bitboard = colorBitboards[color]

    val occupied: Bitboard get() = colorBitboards[WHITE] or colorBitboards[BLACK]

    fun kingSquare(color: Color): Square = lsb(pieces(color, KING))

    /** Pieces of both colors that attack [square], with the given [occupied] squares (blockers). */
    fun attackersTo(square: Square, occupied: Bitboard = this.occupied): Bitboard =
        (Attacks.pawnAttacks(BLACK, square) and pieces(WHITE, PAWN)) or
        (Attacks.pawnAttacks(WHITE, square) and pieces(BLACK, PAWN)) or
        (Attacks.knightAttacks(square) and (pieces(WHITE, KNIGHT) or pieces(BLACK, KNIGHT))) or
        (Attacks.kingAttacks(square) and (pieces(WHITE, KING) or pieces(BLACK, KING))) or
        (Attacks.bishopAttacks(square, occupied) and (bishopsAndQueens(WHITE) or bishopsAndQueens(BLACK))) or
        (Attacks.rookAttacks(square, occupied) and (rooksAndQueens(WHITE) or rooksAndQueens(BLACK)))

    /** Whether any piece of [attacker] attacks [square]. */
    fun isAttacked(square: Square, attacker: Color): Boolean {
        val occupied = this.occupied
        return (Attacks.pawnAttacks(opposite(attacker), square) and pieces(attacker, PAWN)) != EMPTY ||
            (Attacks.knightAttacks(square) and pieces(attacker, KNIGHT)) != EMPTY ||
            (Attacks.kingAttacks(square) and pieces(attacker, KING)) != EMPTY ||
            (Attacks.bishopAttacks(square, occupied) and bishopsAndQueens(attacker)) != EMPTY ||
            (Attacks.rookAttacks(square, occupied) and rooksAndQueens(attacker)) != EMPTY
    }

    /** Whether the side to move is in check. */
    fun inCheck(): Boolean = isAttacked(kingSquare(sideToMove), opposite(sideToMove))

    /** After [makeMove]: whether the player who moved left its own king in check (so the move was illegal). */
    fun leftKingInCheck(): Boolean = isAttacked(kingSquare(opposite(sideToMove)), sideToMove)

    // ---- Draw rules ----

    /**
     * Number of times the current position occurred before in the moves played on this position
     * (same pieces, side to move, castling rights and en passant square, compared by [key]).
     * Only positions since the last capture or pawn move can repeat.
     */
    fun repetitions(): Int {
        var count = 0
        val earliest = maxOf(0, historySize - halfmoveClock)
        var index = historySize - 2 // Previous positions with the same side to move
        while (index >= earliest) {
            if (history[index].key == key) count++
            index -= 2
        }
        return count
    }

    /**
     * Whether no sequence of legal moves can lead to checkmate:
     * only kings, a single minor piece, or only bishops all on squares of the same color.
     */
    fun isInsufficientMaterial(): Boolean {
        val heavyOrPawns = pieces(WHITE, PAWN) or pieces(BLACK, PAWN) or rooksAndQueens(WHITE) or rooksAndQueens(BLACK)
        if (heavyOrPawns != EMPTY) {
            return false
        }
        val knights = pieces(WHITE, KNIGHT) or pieces(BLACK, KNIGHT)
        val bishops = pieces(WHITE, BISHOP) or pieces(BLACK, BISHOP)
        if (popCount(knights or bishops) <= 1) {
            return true
        }
        return knights == EMPTY && (bishops and LIGHT_SQUARES == EMPTY || bishops and DARK_SQUARES == EMPTY)
    }

    private fun bishopsAndQueens(color: Color) = pieces(color, BISHOP) or pieces(color, QUEEN)

    private fun rooksAndQueens(color: Color) = pieces(color, ROOK) or pieces(color, QUEEN)

    // ---- Moves ----

    /** Plays a pseudo-legal move. It may leave the own king in check: see [leftKingInCheck]. */
    fun makeMove(move: Move) {
        val from = move.from
        val to = move.to
        val piece = board[from]
        val us = sideToMove
        val them = opposite(us)
        val captured = if (move.isEnPassant) piece(them, PAWN) else board[to]

        history[historySize++].apply {
            this.move = move
            this.captured = captured
            this.castlingRights = this@Position.castlingRights
            this.enPassantSquare = this@Position.enPassantSquare
            this.halfmoveClock = this@Position.halfmoveClock
            this.key = this@Position.key
        }

        halfmoveClock++
        if (enPassantSquare != NO_SQUARE) {
            key = key xor Zobrist.enPassant(enPassantSquare)
            enPassantSquare = NO_SQUARE
        }

        if (captured != NO_PIECE) {
            removePiece(if (move.isEnPassant) enPassantCaptureSquare(to, us) else to)
            halfmoveClock = 0
        }

        movePiece(from, to)

        if (typeOf(piece) == PAWN) {
            halfmoveClock = 0
            if (move.isPromotion) {
                removePiece(to)
                putPiece(to, piece(us, move.promotionType))
            } else if (move.isDoublePawnPush) {
                val skipped = (from + to) / 2
                // Only set when an enemy pawn can capture, so equal positions get equal keys
                if (Attacks.pawnAttacks(us, skipped) and pieces(them, PAWN) != EMPTY) {
                    enPassantSquare = skipped
                    key = key xor Zobrist.enPassant(skipped)
                }
            }
        } else if (move.isCastling) {
            movePiece(castlingRookFrom(move), castlingRookTo(move))
        }

        key = key xor Zobrist.castling(castlingRights)
        castlingRights = castlingRights and Castling.keptRights[from] and Castling.keptRights[to]
        key = key xor Zobrist.castling(castlingRights)

        if (us == BLACK) {
            fullmoveNumber++
        }
        sideToMove = them
        key = key xor Zobrist.blackToMove
    }

    /** Takes back the last move played with [makeMove], which must be [move]. */
    fun unmakeMove(move: Move) {
        val state = history[--historySize]
        val from = move.from
        val to = move.to
        val them = sideToMove
        val us = opposite(them)

        sideToMove = us
        if (us == BLACK) {
            fullmoveNumber--
        }

        if (move.isPromotion) {
            removePiece(to)
            putPiece(to, piece(us, PAWN))
        } else if (move.isCastling) {
            movePiece(castlingRookTo(move), castlingRookFrom(move))
        }

        movePiece(to, from)

        if (state.captured != NO_PIECE) {
            putPiece(if (move.isEnPassant) enPassantCaptureSquare(to, us) else to, state.captured)
        }

        castlingRights = state.castlingRights
        enPassantSquare = state.enPassantSquare
        halfmoveClock = state.halfmoveClock
        key = state.key
    }

    /** Last move played, or [Move.NONE]. */
    fun lastMove(): Move = if (historySize > 0) history[historySize - 1].move else Move.NONE

    /** Square of the pawn captured en passant by a pawn of [us] moving to [to]. */
    private fun enPassantCaptureSquare(to: Square, us: Color): Square = if (us == WHITE) to - 8 else to + 8

    private fun castlingRookFrom(move: Move): Square = if (move.flags == Move.KING_CASTLE) move.to + 1 else move.to - 2

    private fun castlingRookTo(move: Move): Square = if (move.flags == Move.KING_CASTLE) move.to - 1 else move.to + 1

    private fun putPiece(square: Square, piece: Piece) {
        val bit = bitboardOf(square)
        pieceBitboards[piece] = pieceBitboards[piece] or bit
        colorBitboards[colorOf(piece)] = colorBitboards[colorOf(piece)] or bit
        board[square] = piece
        key = key xor Zobrist.piece(piece, square)
    }

    private fun removePiece(square: Square) {
        val piece = board[square]
        val bit = bitboardOf(square).inv()
        pieceBitboards[piece] = pieceBitboards[piece] and bit
        colorBitboards[colorOf(piece)] = colorBitboards[colorOf(piece)] and bit
        board[square] = NO_PIECE
        key = key xor Zobrist.piece(piece, square)
    }

    private fun movePiece(from: Square, to: Square) {
        val piece = board[from]
        removePiece(from)
        putPiece(to, piece)
    }

    // ---- Hashing ----

    /** Zobrist hash computed from scratch (the [key] is updated incrementally and should always be equal). */
    fun computeKey(): Long {
        var hash = Zobrist.castling(castlingRights)
        for (square in 0 until 64) {
            if (board[square] != NO_PIECE) {
                hash = hash xor Zobrist.piece(board[square], square)
            }
        }
        if (enPassantSquare != NO_SQUARE) {
            hash = hash xor Zobrist.enPassant(enPassantSquare)
        }
        if (sideToMove == BLACK) {
            hash = hash xor Zobrist.blackToMove
        }
        return hash
    }

    // ---- FEN ----

    /** Forsyth-Edwards Notation of the position. */
    fun toFen(): String = buildString {
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val piece = board[square(file, rank)]
                if (piece == NO_PIECE) {
                    empty++
                } else {
                    if (empty > 0) append(empty)
                    empty = 0
                    append(pieceChar(piece))
                }
            }
            if (empty > 0) append(empty)
            if (rank > 0) append('/')
        }
        append(if (sideToMove == WHITE) " w " else " b ")
        if (castlingRights == Castling.NONE) {
            append('-')
        } else {
            if (castlingRights and Castling.WHITE_KINGSIDE != 0) append('K')
            if (castlingRights and Castling.WHITE_QUEENSIDE != 0) append('Q')
            if (castlingRights and Castling.BLACK_KINGSIDE != 0) append('k')
            if (castlingRights and Castling.BLACK_QUEENSIDE != 0) append('q')
        }
        append(' ').append(if (enPassantSquare == NO_SQUARE) "-" else squareName(enPassantSquare))
        append(' ').append(halfmoveClock)
        append(' ').append(fullmoveNumber)
    }

    /** Board diagram (white at the bottom), FEN and key. */
    override fun toString(): String = buildString {
        for (rank in 7 downTo 0) {
            append(rank + 1).append(' ')
            for (file in 0..7) {
                append(' ').append(pieceChar(board[square(file, rank)]))
            }
            append('\n')
        }
        append("   a b c d e f g h\n\n")
        append("FEN: ").append(toFen()).append('\n')
        append("Key: ").append(java.lang.Long.toHexString(key))
    }

    /** Independent copy of the position (without the history of moves, so it cannot unmake previous moves). */
    fun copy(): Position = fromFen(toFen())

    companion object {
        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        /** Maximum number of moves that can be played (and unmade) on a position. */
        const val MAX_HISTORY = 2048

        fun startingPosition(): Position = fromFen(STARTING_FEN)

        /** Parses a FEN. Halfmove clock and fullmove number are optional. */
        fun fromFen(fen: String): Position {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size in 4..6) { "Invalid FEN (expected 4 to 6 fields): $fen" }

            val position = Position()

            val ranks = fields[0].split('/')
            require(ranks.size == 8) { "Invalid FEN (expected 8 ranks): $fen" }
            for ((index, rankText) in ranks.withIndex()) {
                val rank = 7 - index
                var file = 0
                for (char in rankText) {
                    if (char.isDigit()) {
                        file += char - '0'
                    } else {
                        require(file < 8) { "Invalid FEN (rank ${rank + 1} is too long): $fen" }
                        position.putPiece(square(file, rank), parsePiece(char))
                        file++
                    }
                }
                require(file == 8) { "Invalid FEN (rank ${rank + 1} does not have 8 squares): $fen" }
            }
            for (color in listOf(WHITE, BLACK)) {
                require(popCount(position.pieces(color, KING)) == 1) { "Invalid FEN (each player must have one king): $fen" }
            }

            position.sideToMove = when (fields[1]) {
                "w" -> WHITE
                "b" -> BLACK
                else -> throw IllegalArgumentException("Invalid FEN (side to move must be w or b): $fen")
            }

            if (fields[2] != "-") {
                for (char in fields[2]) {
                    position.castlingRights = position.castlingRights or when (char) {
                        'K' -> Castling.WHITE_KINGSIDE
                        'Q' -> Castling.WHITE_QUEENSIDE
                        'k' -> Castling.BLACK_KINGSIDE
                        'q' -> Castling.BLACK_QUEENSIDE
                        else -> throw IllegalArgumentException("Invalid FEN (castling rights): $fen")
                    }
                }
            }
            position.castlingRights = position.castlingRights and position.validCastlingRights()

            if (fields[3] != "-") {
                val square = parseSquare(fields[3])
                val us = position.sideToMove
                // Like after a double push, only kept when an enemy pawn can capture
                if (Attacks.pawnAttacks(opposite(us), square) and position.pieces(us, PAWN) != EMPTY) {
                    position.enPassantSquare = square
                }
            }

            position.halfmoveClock = fields.getOrNull(4)?.toIntOrNull() ?: 0
            position.fullmoveNumber = fields.getOrNull(5)?.toIntOrNull() ?: 1

            require(!position.leftKingInCheck()) { "Invalid FEN (the side not to move is in check): $fen" }

            position.key = position.computeKey()
            return position
        }
    }

    /** Castling rights that are possible with the current placement of kings and rooks. */
    private fun validCastlingRights(): Int {
        var valid = Castling.NONE
        if (board[E1] == piece(WHITE, KING)) {
            if (board[H1] == piece(WHITE, ROOK)) valid = valid or Castling.WHITE_KINGSIDE
            if (board[A1] == piece(WHITE, ROOK)) valid = valid or Castling.WHITE_QUEENSIDE
        }
        if (board[E8] == piece(BLACK, KING)) {
            if (board[H8] == piece(BLACK, ROOK)) valid = valid or Castling.BLACK_KINGSIDE
            if (board[A8] == piece(BLACK, ROOK)) valid = valid or Castling.BLACK_QUEENSIDE
        }
        return valid
    }
}
