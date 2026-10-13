package core

import me.carleslc.kotlin.extensions.strings.splitLines
import pieces.*
import java.io.File
import java.util.*

private val KNIGHT_OFFSETS = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
private val KING_OFFSETS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
private val LINEAR_DIRECTIONS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
private val DIAGONAL_DIRECTIONS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

/** Random keys to hash a board into a Long (Zobrist hashing). */
private val ZOBRIST_PIECES = Random(2019).let { random -> Array(12) { LongArray(64) { random.nextLong() } } }
private val ZOBRIST_BLACK_TURN = Random(2018).nextLong()
private val ZOBRIST_CASTLING = Random(2020).let { random -> LongArray(Castling.values().size) { random.nextLong() } }
private val ZOBRIST_EN_PASSANT = Random(2021).let { random -> LongArray(8) { random.nextLong() } }

/**
 * Immutable chess position: playing a move returns a new board.
 * Status and legal moves are computed lazily for the player in turn.
 */
class Board private constructor(internal val board: Chessboard,
                                val turn: Player,
                                /** Castling rights not lost yet. */
                                val castling: Set<Castling>,
                                /** Position where a pawn can capture en passant (only set when an opponent pawn can capture). */
                                val enPassant: Position?,
                                private val captureOrPawnCounter: Int = 0,
                                val fullMoveNumber: Int = 1) {

    companion object Factory {

        /** Half moves without captures nor pawn movements to claim a draw (fifty moves for each player). */
        const val FIFTY_MOVES_RULE = 100

        const val STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun new(): Board = fromFen(STARTING_FEN)

        /** Parses a board drawn like [toString]. Castling rights are given if kings and rooks are in their initial positions. */
        fun parse(board: String, turn: Player = Player.Declarations.START): Board {
            fun syntaxError(message: String) = IllegalArgumentException("Invalid board syntax: $message")
            fun syntaxError(e: Exception) = IllegalArgumentException("Invalid board syntax", e)

            val lines = board.splitLines()
            val chessboard = ChessboardMap.empty()

            val rowRegex = """^.*\[(([■□] [RNBQKP]|_{3}).*){8}].*$""".toRegex()
            val pieceRegex = """[■□] [RNBQKP]|_{3}""".toRegex()

            fun parsePiece(rawPiece: String): Piece? {
                if (rawPiece == "___") {
                    return null
                }
                val player = if (rawPiece[0] == Player.WHITE.notation) Player.WHITE else Player.BLACK
                return PieceType.fromNotation(rawPiece[2]).create(player)
            }

            fun parseRow(i: Int, row: String) {
                var j = 0
                for (rawPiece in pieceRegex.findAll(row)) {
                    chessboard[i, j++] = parsePiece(rawPiece.value)
                }
            }

            try {
                val encodedBoard = lines.subList(1, 9)
                for (i in encodedBoard.indices) {
                    val row = encodedBoard[i]
                    if (!rowRegex.matches(row)) {
                        throw syntaxError("Row at line ${i + 2} is invalid")
                    }
                    parseRow(i, row)
                }
            } catch (e: Exception) {
                throw syntaxError(e)
            }

            return Board(chessboard, turn, validCastling(chessboard, Castling.values().toSet()), null)
        }

        fun parseFile(boardPath: String, turn: Player = Player.Declarations.START) = parse(File(boardPath).readText(), turn)

        /** Parses a position in Forsyth-Edwards Notation. */
        fun fromFen(fen: String): Board {
            fun fenError(message: String) = IllegalArgumentException("Invalid FEN ($message): $fen")

            val fields = fen.trim().split(Regex("\\s+"))
            if (fields.size !in 4..6) throw fenError("expected 4 to 6 fields")

            val chessboard = ChessboardMap.empty()
            val rows = fields[0].split('/')
            if (rows.size != 8) throw fenError("expected 8 rows")
            for ((row, text) in rows.withIndex()) {
                var column = 0
                for (char in text) {
                    if (char.isDigit()) {
                        column += char - '0'
                    } else {
                        if (column > 7) throw fenError("row ${8 - row} is too long")
                        val player = if (char.isUpperCase()) Player.WHITE else Player.BLACK
                        chessboard[row, column++] = PieceType.fromNotation(char).create(player)
                    }
                }
                if (column != 8) throw fenError("row ${8 - row} does not have 8 squares")
            }

            val turn = when (fields[1]) {
                "w" -> Player.WHITE
                "b" -> Player.BLACK
                else -> throw fenError("turn must be w or b")
            }

            val castling = if (fields[2] == "-") emptySet() else fields[2].map { char ->
                Castling.values().firstOrNull { it.notation == char } ?: throw fenError("castling rights")
            }.toSet()

            val enPassant = if (fields[3] == "-") null else Position.fromAlgebraicChessNotation(fields[3])

            val board = Board(chessboard, turn, validCastling(chessboard, castling),
                    enPassant?.takeIf { canCaptureEnPassant(chessboard, it, turn) },
                    fields.getOrNull(4)?.toIntOrNull() ?: 0,
                    fields.getOrNull(5)?.toIntOrNull() ?: 1)

            for (player in Player.values()) {
                if (board.getPieces(player).count { it.value.type == PieceType.KING } != 1) throw fenError("each player must have one king")
            }
            if (board.isInCheck(turn.opponent())) throw fenError("the player not in turn is in check")

            return board
        }

        /** Castling rights are lost when the king or the rook are not in their initial positions. */
        private fun validCastling(chessboard: Chessboard, castling: Set<Castling>): Set<Castling> = castling.filter {
            chessboard[it.kingFrom] == King(it.player) && chessboard[it.rookFrom] == Rook(it.player)
        }.toSet()

        /** Whether a pawn of [turn] could capture en passant to [position]. */
        private fun canCaptureEnPassant(chessboard: Chessboard, position: Position, turn: Player): Boolean {
            // The opponent pawn that moved two rows is in the same row as the capturing pawns
            val row = if (turn == Player.WHITE) position.row + 1 else position.row - 1
            return listOf(position.column - 1, position.column + 1).any { column ->
                column in 0..7 && chessboard[row, column] == Pawn(turn)
            }
        }

    }

    /** Legal moves for the player in turn, sorted to search the most promising first (captures by MVV-LVA). */
    val legalMoves: List<Move> by lazy {
        pseudoLegalMoves().filter { isLegal(it) }.sortedByDescending { it.orderScore }
    }

    /** Whether the player in turn is in check. */
    val inCheck: Boolean by lazy { isInCheck(turn) }

    /** Status of the player in turn. */
    val status: Status by lazy {
        when {
            legalMoves.isEmpty() -> if (inCheck) Status.CHECKMATE else Status.STALEMATE
            captureOrPawnCounter >= FIFTY_MOVES_RULE -> Status.NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES
            hasInsufficientMaterial() -> Status.INSUFFICIENT_MATERIAL
            inCheck -> Status.CHECK
            else -> Status.PLAYING
        }
    }

    /** Hash of the position (pieces, turn, castling rights and en passant), suitable as a transposition table key. */
    val key: Long by lazy {
        var hash = if (turn == Player.BLACK) ZOBRIST_BLACK_TURN else 0L
        board.forEach { pos, piece ->
            if (piece != null) {
                hash = hash xor ZOBRIST_PIECES[piece.player.ordinal * 6 + piece.type.ordinal][pos.row * 8 + pos.column]
            }
        }
        castling.forEach { hash = hash xor ZOBRIST_CASTLING[it.ordinal] }
        enPassant?.let { hash = hash xor ZOBRIST_EN_PASSANT[it.column] }
        hash
    }

    fun isEndGame() = status.isTerminal()

    fun getPieces(player: Player = turn): Set<Map.Entry<Position, Piece>> = board.getPieces(player)

    /** Returns the board after playing [move], which must be a legal move of this board. */
    fun play(move: Move): Board {
        val chessboard = board.copy()
        val player = move.piece.player
        chessboard[move.from] = null
        chessboard[move.capturedPosition] = null
        chessboard[move.to] = move.promotion?.create(player) ?: move.piece
        move.castling?.let {
            chessboard[it.rookFrom] = null
            chessboard[it.rookTo] = Rook(player)
        }

        // Moving the king or a rook, or capturing a rook, loses the castling rights
        val remainingCastling = castling.filter {
            move.from != it.kingFrom && move.from != it.rookFrom && move.to != it.rookFrom
        }.toSet()

        val pawn = move.piece.type == PieceType.PAWN
        val doublePush = pawn && Math.abs(move.to.row - move.from.row) == 2
        val skipped = Position((move.from.row + move.to.row) / 2, move.from.column)
        val enPassant = if (doublePush && canCaptureEnPassant(chessboard, skipped, turn.opponent())) skipped else null

        val counter = if (pawn || move.isCapture()) 0 else captureOrPawnCounter + 1
        val fullMove = if (turn == Player.BLACK) fullMoveNumber + 1 else fullMoveNumber

        return Board(chessboard, turn.opponent(), remainingCastling, enPassant, counter, fullMove)
    }

    private fun pseudoLegalMoves(): List<Move> = getPieces().flatMap { it.value.availableMovesOn(it.key, this) }

    /** A move is legal if it does not leave the own king in check. It is tested on this board and then undone. */
    private fun isLegal(move: Move): Boolean {
        board[move.from] = null
        board[move.capturedPosition] = null
        board[move.to] = move.piece
        val legal = !isInCheck(turn)
        board[move.to] = null
        board[move.capturedPosition] = move.captured
        board[move.from] = move.piece
        return legal
    }

    private fun isInCheck(player: Player): Boolean {
        val king = getPieces(player).firstOrNull { it.value.type == PieceType.KING } ?: return false
        return isAttacked(king.key, player.opponent())
    }

    /** Whether [position] is attacked by any piece of [attacker]. */
    fun isAttacked(position: Position, attacker: Player): Boolean {
        val row = position.row
        val column = position.column

        fun isPiece(i: Int, j: Int, vararg types: PieceType): Boolean {
            if (i !in 0..7 || j !in 0..7) return false
            val piece = board[i, j] ?: return false
            return piece.player == attacker && piece.type in types
        }

        fun isSliderAttacking(directions: List<Pair<Int, Int>>, vararg types: PieceType): Boolean {
            for ((di, dj) in directions) {
                var i = row + di
                var j = column + dj
                while (i in 0..7 && j in 0..7 && board[i, j] == null) {
                    i += di
                    j += dj
                }
                if (isPiece(i, j, *types)) return true
            }
            return false
        }

        // A white pawn attacks the row above (towards row 0), so it would be one row below the attacked position
        val pawnRow = if (attacker == Player.WHITE) row + 1 else row - 1

        return isPiece(pawnRow, column - 1, PieceType.PAWN) || isPiece(pawnRow, column + 1, PieceType.PAWN)
                || KNIGHT_OFFSETS.any { (di, dj) -> isPiece(row + di, column + dj, PieceType.KNIGHT) }
                || KING_OFFSETS.any { (di, dj) -> isPiece(row + di, column + dj, PieceType.KING) }
                || isSliderAttacking(LINEAR_DIRECTIONS, PieceType.ROOK, PieceType.QUEEN)
                || isSliderAttacking(DIAGONAL_DIRECTIONS, PieceType.BISHOP, PieceType.QUEEN)
    }

    /** Only kings, kings and a single minor piece, or kings and bishops on squares of the same color. */
    private fun hasInsufficientMaterial(): Boolean {
        val pieces = (getPieces(Player.WHITE) + getPieces(Player.BLACK)).filter { it.value.type != PieceType.KING }
        return when {
            pieces.isEmpty() -> true
            pieces.size == 1 -> pieces[0].value.type == PieceType.KNIGHT || pieces[0].value.type == PieceType.BISHOP
            else -> pieces.all { it.value.type == PieceType.BISHOP }
                    && pieces.map { (it.key.row + it.key.column) % 2 }.distinct().size == 1
        }
    }

    /**
     * Standard algebraic notation of a legal move: e4, Nf3, Ngf6, R1e2, exd6, Qxh7#, a8=Q+, O-O.
     */
    fun toSan(move: Move): String {
        val san = StringBuilder()
        val castling = move.castling
        if (castling != null) {
            san.append(castling.san)
        } else {
            val type = move.piece.type
            if (type == PieceType.PAWN) {
                if (move.isCapture()) san.append(move.from.toString()[0])
            } else {
                san.append(type.notation)
                // Disambiguation when another piece of the same type can move to the same position
                val others = legalMoves.filter { it.piece == move.piece && it.to == move.to && it.from != move.from }.map { it.from }
                if (others.isNotEmpty()) {
                    when {
                        others.none { it.column == move.from.column } -> san.append(move.from.toString()[0])
                        others.none { it.row == move.from.row } -> san.append(move.from.toString()[1])
                        else -> san.append(move.from)
                    }
                }
            }
            if (move.isCapture()) san.append('x')
            san.append(move.to)
            move.promotion?.let { san.append('=').append(it.notation) }
        }
        val next = play(move)
        when {
            next.status == Status.CHECKMATE -> san.append('#')
            next.inCheck -> san.append('+')
        }
        return san.toString()
    }

    /** Parses a move in standard algebraic notation (Nf3, exd6 e.p., O-O, a8=Q+) or long algebraic notation (e2e4, e7e8q). */
    fun parseMove(text: String): Move? {
        val notation = text.trim().replace(" ", "").removeSuffix("e.p.").replace('0', 'O').trimEnd('+', '#', '!', '?')
        return legalMoves.firstOrNull { it.notation == notation.toLowerCase() }
                ?: legalMoves.firstOrNull { toSan(it).trimEnd('+', '#') == notation }
    }

    /** Forsyth-Edwards Notation of the position. */
    fun toFen(): String {
        val rows = board.rows.joinToString("/") { i ->
            var row = ""
            var empty = 0
            for (piece in board[i]) {
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) row += empty
                    empty = 0
                    val notation = piece.type.notation
                    row += if (piece.player == Player.WHITE) notation else notation.toLowerCase()
                }
            }
            if (empty > 0) row += empty
            row
        }
        val turn = if (turn == Player.WHITE) "w" else "b"
        val castling = if (castling.isEmpty()) "-" else castling.sortedBy { it.ordinal }.map { it.notation }.joinToString("")
        return "$rows $turn $castling ${enPassant ?: "-"} $captureOrPawnCounter $fullMoveNumber"
    }

    fun printStatus() = println(status.getDescription(turn))

    operator fun get(p: Position): Piece? = board[p]

    operator fun get(i: Int, j: Int): Piece? = board[i, j]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Board

        return board == other.board && turn == other.turn && castling == other.castling && enPassant == other.enPassant
    }

    override fun hashCode() = key.hashCode()

    override fun toString(): String {
        val builder = StringBuilder()
        val columns = board.columns.joinToString(separator="    ", prefix="    ") { Position.getAlgebraicChessColumn(it).toString() }
        builder.appendln(columns)
        for (i in board.rows) {
            val p = 8 - i
            builder.append("$p ")
            builder.append(board[i].joinToString(separator="  ", prefix="[", postfix="]") { it?.toString() ?: "___" })
            builder.appendln(" $p")
        }
        builder.appendln(columns)
        return builder.toString()
    }

}
