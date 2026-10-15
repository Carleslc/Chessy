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

    /** Index (0 to 3) of a single castling right. */
    fun index(right: Int): Int = Integer.numberOfTrailingZeros(right)
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

    /** Starting square of the rook of each castling right (by [Castling.index]): h1 and a1 (h8, a8) but in Chess960. */
    private val castlingRooks = IntArray(4) { NO_SQUARE }

    /** Castling rights kept when a piece moves from or to each square (moving the king or a castling rook, or capturing it). */
    private val keptRights = IntArray(64) { Castling.ALL }

    /**
     * Chess960 (https://chessprogramming.org/Chess960): castling is written as the king taking its own rook in UCI,
     * and the castling rights in FEN as files when the rook is not the outermost one (X-FEN). The rules are the same.
     */
    var chess960 = false

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

        fun copyFrom(other: State) {
            move = other.move
            captured = other.captured
            castlingRights = other.castlingRights
            enPassantSquare = other.enPassantSquare
            halfmoveClock = other.halfmoveClock
            key = other.key
        }
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

    /** Starting square of the rook of a castling [right] (a single right, such as [Castling.WHITE_KINGSIDE]). */
    fun castlingRook(right: Int): Square = castlingRooks[Castling.index(right)]

    // ---- Moves ----

    /** Plays a pseudo-legal move. It may leave the own king in check: see [leftKingInCheck]. */
    fun makeMove(move: Move) {
        val from = move.from
        val to = move.to
        val piece = board[from]
        val us = sideToMove
        val them = opposite(us)
        val captured = when {
            move.isEnPassant -> piece(them, PAWN)
            move.isCastling -> NO_PIECE // The king moves to the square of its own rook
            else -> board[to]
        }

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

        if (move.isCastling) {
            // King and rook may swap squares or stay in place (Chess960): take both, then put both
            val rook = board[to]
            removePiece(from)
            removePiece(to)
            putPiece(move.castlingKingTo, piece)
            putPiece(move.castlingRookTo, rook)
        } else {
            movePiece(from, to)
        }

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
        }

        key = key xor Zobrist.castling(castlingRights)
        castlingRights = castlingRights and keptRights[from] and keptRights[to]
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

        if (move.isCastling) {
            val king = board[move.castlingKingTo]
            val rook = board[move.castlingRookTo]
            removePiece(move.castlingKingTo)
            removePiece(move.castlingRookTo)
            putPiece(from, king)
            putPiece(to, rook)
        } else {
            if (move.isPromotion) {
                removePiece(to)
                putPiece(to, piece(us, PAWN))
            }
            movePiece(to, from)
        }

        if (state.captured != NO_PIECE) {
            putPiece(if (move.isEnPassant) enPassantCaptureSquare(to, us) else to, state.captured)
        }

        castlingRights = state.castlingRights
        enPassantSquare = state.enPassantSquare
        halfmoveClock = state.halfmoveClock
        key = state.key
    }

    /**
     * Passes the turn to the opponent without moving (null move, only for the search: https://www.chessprogramming.org/Null_Move).
     * It breaks the repetitions: positions before a null move cannot be repeated after it.
     */
    fun makeNullMove() {
        history[historySize++].apply {
            this.move = Move.NONE
            this.captured = NO_PIECE
            this.castlingRights = this@Position.castlingRights
            this.enPassantSquare = this@Position.enPassantSquare
            this.halfmoveClock = this@Position.halfmoveClock
            this.key = this@Position.key
        }
        if (enPassantSquare != NO_SQUARE) {
            key = key xor Zobrist.enPassant(enPassantSquare)
            enPassantSquare = NO_SQUARE
        }
        halfmoveClock = 0
        sideToMove = opposite(sideToMove)
        key = key xor Zobrist.blackToMove
    }

    fun unmakeNullMove() {
        val state = history[--historySize]
        sideToMove = opposite(sideToMove)
        enPassantSquare = state.enPassantSquare
        halfmoveClock = state.halfmoveClock
        key = state.key
    }

    /** Whether [color] has pieces other than pawns and king (without them, zugzwang is likely). */
    fun hasNonPawnMaterial(color: Color): Boolean =
        pieces(color, KNIGHT) or pieces(color, BISHOP) or rooksAndQueens(color) != EMPTY

    /** Last move played, or [Move.NONE]. */
    fun lastMove(): Move = if (historySize > 0) history[historySize - 1].move else Move.NONE

    /** Square of the pawn captured en passant by a pawn of [us] moving to [to]. */
    private fun enPassantCaptureSquare(to: Square, us: Color): Square = if (us == WHITE) to - 8 else to + 8

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
            for (right in intArrayOf(Castling.WHITE_KINGSIDE, Castling.WHITE_QUEENSIDE, Castling.BLACK_KINGSIDE, Castling.BLACK_QUEENSIDE)) {
                if (castlingRights and right != 0) append(castlingChar(right))
            }
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

    /** Independent copy of the position, including the moves played (to unmake them and detect repetitions). */
    fun copy(): Position {
        val copy = Position()
        pieceBitboards.copyInto(copy.pieceBitboards)
        colorBitboards.copyInto(copy.colorBitboards)
        board.copyInto(copy.board)
        copy.sideToMove = sideToMove
        copy.castlingRights = castlingRights
        castlingRooks.copyInto(copy.castlingRooks)
        keptRights.copyInto(copy.keptRights)
        copy.chess960 = chess960
        copy.enPassantSquare = enPassantSquare
        copy.halfmoveClock = halfmoveClock
        copy.fullmoveNumber = fullmoveNumber
        copy.key = key
        for (i in 0 until historySize) {
            copy.history[i].copyFrom(history[i])
        }
        copy.historySize = historySize
        return copy
    }

    companion object {
        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        /** Maximum number of moves that can be played (and unmade) on a position. */
        const val MAX_HISTORY = 2048

        fun startingPosition(): Position = fromFen(STARTING_FEN)

        /** Number of the standard starting position among the Chess960 starting positions. */
        const val CHESS960_STANDARD = 518

        /**
         * FEN of the Chess960 starting position [number] (0 to 959), in the numbering of Reinhard Scharnagl
         * (https://chessprogramming.org/Chess960): the bishops on squares of different colors, then the queen, the knights,
         * and the king between the rooks. Number 518 is the standard starting position.
         */
        fun chess960Fen(number: Int): String {
            require(number in 0..959) { "Chess960 positions are numbered from 0 to 959" }
            val pieces = CharArray(8) { ' ' }
            var n = number
            pieces[2 * (n % 4) + 1] = 'B' // Light-squared bishop on b, d, f or h
            n /= 4
            pieces[2 * (n % 4)] = 'B' // Dark-squared bishop on a, c, e or g
            n /= 4
            fun placeOnEmpty(index: Int, piece: Char) {
                pieces[pieces.indices.filter { pieces[it] == ' ' }[index]] = piece
            }
            placeOnEmpty(n % 6, 'Q')
            n /= 6
            // The knights on two of the five empty squares (10 combinations), then rook, king and rook
            val knights = listOf(0 to 1, 0 to 2, 0 to 3, 0 to 4, 1 to 2, 1 to 3, 1 to 4, 2 to 3, 2 to 4, 3 to 4)[n]
            placeOnEmpty(knights.second, 'N')
            placeOnEmpty(knights.first, 'N')
            for (piece in "RKR") placeOnEmpty(0, piece)
            val white = String(pieces)
            return "${white.lowercase()}/pppppppp/8/8/8/8/PPPPPPPP/$white w KQkq - 0 1"
        }

        /** Parses a FEN. Halfmove clock and fullmove number are optional. */
        fun fromFen(fen: String): Position = Position().apply { setFen(fen) }

        private val SPACES = Regex("\\s+")
    }

    /**
     * Replaces this position with the one of a FEN, forgetting the moves played.
     * Cheaper than [fromFen] when many positions are read one after the other (for example to tune the evaluation).
     */
    fun setFen(fen: String) {
        val fields = fen.trim().split(SPACES)
        require(fields.size in 4..6) { "Invalid FEN (expected 4 to 6 fields): $fen" }

        pieceBitboards.fill(EMPTY)
        colorBitboards.fill(EMPTY)
        board.fill(NO_PIECE)
        castlingRights = Castling.NONE
        castlingRooks.fill(NO_SQUARE)
        keptRights.fill(Castling.ALL)
        enPassantSquare = NO_SQUARE
        historySize = 0

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
                    putPiece(square(file, rank), parsePiece(char))
                    file++
                }
            }
            require(file == 8) { "Invalid FEN (rank ${rank + 1} does not have 8 squares): $fen" }
        }
        for (color in listOf(WHITE, BLACK)) {
            require(popCount(pieces(color, KING)) == 1) { "Invalid FEN (each player must have one king): $fen" }
        }

        sideToMove = when (fields[1]) {
            "w" -> WHITE
            "b" -> BLACK
            else -> throw IllegalArgumentException("Invalid FEN (side to move must be w or b): $fen")
        }

        if (fields[2] != "-") {
            for (char in fields[2]) {
                require(char.uppercaseChar() in "KQABCDEFGH") { "Invalid FEN (castling rights): $fen" }
                addCastlingRight(char)
            }
        }

        if (fields[3] != "-") {
            val square = parseSquare(fields[3])
            val us = sideToMove
            // Like after a double push, only kept when an enemy pawn can capture
            if (Attacks.pawnAttacks(opposite(us), square) and pieces(us, PAWN) != EMPTY) {
                enPassantSquare = square
            }
        }

        halfmoveClock = fields.getOrNull(4)?.toIntOrNull() ?: 0
        fullmoveNumber = fields.getOrNull(5)?.toIntOrNull() ?: 1

        require(!leftKingInCheck()) { "Invalid FEN (the side not to move is in check): $fen" }

        key = computeKey()
    }

    /**
     * Adds the castling right of a FEN letter: K or Q for the outermost rook on each side of the king (X-FEN),
     * or the file of the rook (Shredder-FEN, for Chess960). Rights without their king and rook on the first rank are ignored.
     */
    private fun addCastlingRight(char: Char) {
        val color = if (char.isUpperCase()) WHITE else BLACK
        val rank = if (color == WHITE) 0 else 7
        val king = kingSquare(color)
        if (rankOf(king) != rank) return
        val rook = piece(color, ROOK)
        val rookFile = when (val letter = char.uppercaseChar()) {
            'K' -> (7 downTo fileOf(king) + 1).firstOrNull { board[square(it, rank)] == rook }
            'Q' -> (0 until fileOf(king)).firstOrNull { board[square(it, rank)] == rook }
            else -> (letter - 'A').takeIf { board[square(it, rank)] == rook && it != fileOf(king) }
        } ?: return
        val right = if (rookFile > fileOf(king)) Castling.kingside(color) else Castling.queenside(color)
        val rookSquare = square(rookFile, rank)
        castlingRights = castlingRights or right
        castlingRooks[Castling.index(right)] = rookSquare
        keptRights[king] = keptRights[king] and (Castling.kingside(color) or Castling.queenside(color)).inv()
        keptRights[rookSquare] = keptRights[rookSquare] and right.inv()
    }

    /** FEN letter of a castling right: K or Q (k, q) if its rook is the outermost one on that side, else its file. */
    private fun castlingChar(right: Int): Char {
        val rook = castlingRook(right)
        val color = if (right == Castling.WHITE_KINGSIDE || right == Castling.WHITE_QUEENSIDE) WHITE else BLACK
        val kingside = right == Castling.kingside(color)
        val outer = if (kingside) (fileOf(rook) + 1..7) else (0 until fileOf(rook))
        val outermost = outer.none { board[square(it, rankOf(rook))] == piece(color, ROOK) }
        val letter = if (outermost) (if (kingside) 'K' else 'Q') else 'A' + fileOf(rook)
        return if (color == WHITE) letter else letter.lowercaseChar()
    }
}
