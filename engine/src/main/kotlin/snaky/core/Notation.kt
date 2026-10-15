package snaky.core

/**
 * Move notations:
 *
 * - Standard Algebraic Notation (SAN): `e4`, `Nf3`, `Ngf6`, `N4f6`, `Rxf3`, `Nf3+`, `O-O`, `O-O-O`, `exd6 e.p.`,
 *   `Qh1#`, `a8=Q`, `bxa8=N+`. It is the notation of PGN files.
 * - UCI (long algebraic notation): `e2e4`, `e1g1` (castling), `e7e8q` (promotion). It is the notation of the UCI protocol.
 *   In Chess960 castling is written as the king taking its own rook (`e1h1`).
 */
object Notation {

    private const val PIECE_LETTERS = "PNBRQK"

    /** SAN of a legal [move] of the [position]. */
    fun toSan(position: Position, move: Move): String {
        val san = StringBuilder()

        when (move.flags) {
            Move.KING_CASTLE -> san.append("O-O")
            Move.QUEEN_CASTLE -> san.append("O-O-O")
            else -> {
                val type = typeOf(position.pieceOn(move.from))
                if (type == PAWN) {
                    if (move.isCapture) san.append('a' + fileOf(move.from))
                } else {
                    san.append(PIECE_LETTERS[type])
                    san.append(disambiguation(position, move, type))
                }
                if (move.isCapture) san.append('x')
                san.append(squareName(move.to))
                if (move.isPromotion) san.append('=').append(PIECE_LETTERS[move.promotionType])
            }
        }

        position.makeMove(move)
        if (position.inCheck()) {
            san.append(if (MoveGenerator.legalMoves(position).isEmpty()) '#' else '+')
        }
        position.unmakeMove(move)

        return san.toString()
    }

    /** When several pieces of the same type can move to the same square: file if enough, else rank, else both. */
    private fun disambiguation(position: Position, move: Move, type: PieceType): String {
        val others = MoveGenerator.legalMoves(position).toList().filter {
            it.to == move.to && it.from != move.from && typeOf(position.pieceOn(it.from)) == type
        }
        return when {
            others.isEmpty() -> ""
            others.none { fileOf(it.from) == fileOf(move.from) } -> ('a' + fileOf(move.from)).toString()
            others.none { rankOf(it.from) == rankOf(move.from) } -> ('1' + rankOf(move.from)).toString()
            else -> squareName(move.from)
        }
    }

    private val SAN_REGEX = Regex("^([NBRQK])?([a-h])?([1-8])?x?([a-h][1-8])(?:=?([NBRQnbrq]))?$")

    /**
     * Parses a move in SAN. It is lenient: check and annotation symbols (`+`, `#`, `++`, `!`, `?`), `e.p.`,
     * castling with zeros (`0-0`) and the capture symbol are optional, and a missing promotion means a queen.
     *
     * @throws IllegalArgumentException if the move is invalid, illegal or ambiguous.
     */
    fun parseSan(position: Position, text: String): Move {
        val san = text.trim()
            .replace("e.p.", "").replace(" ", "")
            .replace('0', 'O')
            .trimEnd('+', '#', '!', '?')
        val legalMoves = MoveGenerator.legalMoves(position).toList()

        if (san == "O-O" || san == "O-O-O") {
            val flag = if (san == "O-O") Move.KING_CASTLE else Move.QUEEN_CASTLE
            return legalMoves.firstOrNull { it.flags == flag } ?: throw IllegalArgumentException("Illegal move: $text")
        }

        val match = SAN_REGEX.matchEntire(san) ?: throw IllegalArgumentException("Invalid move notation: $text")
        val (pieceLetter, fromFile, fromRank, to, promotionLetter) = match.destructured
        val type = if (pieceLetter.isEmpty()) PAWN else PIECE_LETTERS.indexOf(pieceLetter[0])
        val target = parseSquare(to)
        val promotion = if (promotionLetter.isEmpty()) QUEEN else PIECE_LETTERS.indexOf(promotionLetter[0].uppercaseChar())

        val candidates = legalMoves.filter { move ->
            move.to == target && typeOf(position.pieceOn(move.from)) == type
                && (fromFile.isEmpty() || fileOf(move.from) == fromFile[0] - 'a')
                && (fromRank.isEmpty() || rankOf(move.from) == fromRank[0] - '1')
                && (!move.isPromotion || move.promotionType == promotion)
        }
        return when (candidates.size) {
            1 -> candidates[0]
            0 -> throw IllegalArgumentException("Illegal move: $text")
            else -> throw IllegalArgumentException("Ambiguous move: $text (${candidates.joinToString { toSan(position, it) }})")
        }
    }

    /**
     * Parses a legal move in UCI notation, such as `e2e4` or `e7e8q`.
     *
     * @throws IllegalArgumentException if the move is invalid or illegal.
     */
    fun parseUci(position: Position, text: String): Move {
        val uci = text.trim().lowercase()
        val legalMoves = MoveGenerator.legalMoves(position).toList()
        // Castling in the notation of the other mode is accepted too when it is not another move
        return legalMoves.firstOrNull { it.toUci(position.chess960) == uci }
            ?: legalMoves.firstOrNull { it.isCastling && it.toUci(!position.chess960) == uci }
            ?: throw IllegalArgumentException("Illegal move: $text")
    }

    /** UCI notation of a [move] of the [position] (castling as the king taking its rook in Chess960). */
    fun toUci(position: Position, move: Move): String = move.toUci(position.chess960)

    private val UCI_REGEX = Regex("^[a-h][1-8][a-h][1-8][nbrqNBRQ]?$")

    /** Parses a legal move in UCI or SAN notation. */
    fun parseMove(position: Position, text: String): Move =
        if (UCI_REGEX.matches(text.trim())) parseUci(position, text) else parseSan(position, text)
}
