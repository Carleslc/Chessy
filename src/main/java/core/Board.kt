package core

import me.carleslc.kotlin.extensions.strings.splitLines
import pieces.*
import java.io.File
import java.util.*

private val KNIGHT_OFFSETS = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
private val KING_OFFSETS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
private val LINEAR_DIRECTIONS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
private val DIAGONAL_DIRECTIONS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

/** Random keys to hash a board into a Long (Zobrist hashing), indexed by [piece][square]. */
private val ZOBRIST_PIECES = Random(2019).let { random -> Array(12) { LongArray(64) { random.nextLong() } } }
private val ZOBRIST_BLACK_TURN = Random(2018).nextLong()

/**
 * Immutable chess position: playing a move returns a new board.
 * Status and legal moves are computed lazily for the player in turn.
 */
class Board private constructor(internal val board: Chessboard,
                                val turn: Player,
                                private val captureOrPawnCounter: Int = 0) {

    companion object Factory {

        /** Half moves without captures nor pawn movements to claim a draw (fifty moves for each player). */
        const val FIFTY_MOVES_RULE = 100

        fun new(): Board {
            val chessboard = ChessboardMap.empty()

            fun fillRow(row: Int, player: Player) {
                chessboard[row, 0] = Rook(player)
                chessboard[row, 1] = Knight(player)
                chessboard[row, 2] = Bishop(player)
                chessboard[row, 3] = Queen(player)
                chessboard[row, 4] = King(player)
                chessboard[row, 5] = Bishop(player)
                chessboard[row, 6] = Knight(player)
                chessboard[row, 7] = Rook(player)
            }

            fun fillPawns(row: Int, player: Player) {
                for (column in 0..7) {
                    chessboard[row, column] = Pawn(player)
                }
            }

            fillRow(0, Player.BLACK)
            fillPawns(1, Player.BLACK)
            // board[2][*] to board[5][*] are empty positions
            fillPawns(6, Player.WHITE)
            fillRow(7, Player.WHITE)

            return Board(chessboard, Player.Declarations.START)
        }

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
                return when (rawPiece[2]) {
                    'R' -> Rook(player)
                    'N' -> Knight(player)
                    'B' -> Bishop(player)
                    'Q' -> Queen(player)
                    'K' -> King(player)
                    'P' -> Pawn(player)
                    else -> throw syntaxError("Not a piece")
                }
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

            return Board(chessboard, turn)
        }

        fun parseFile(boardPath: String, turn: Player = Player.Declarations.START) = parse(File(boardPath).readText(), turn)

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

    /** Hash of the position (pieces and turn), suitable as a transposition table key. */
    val key: Long by lazy {
        var hash = if (turn == Player.BLACK) ZOBRIST_BLACK_TURN else 0L
        board.forEach { pos, piece ->
            if (piece != null) {
                hash = hash xor ZOBRIST_PIECES[piece.player.ordinal * 6 + piece.type.ordinal][pos.row * 8 + pos.column]
            }
        }
        hash
    }

    fun isEndGame() = status.isTerminal()

    fun getPieces(player: Player = turn): Set<Map.Entry<Position, Piece>> = board.getPieces(player)

    /** Returns the board after playing [move], which must be a legal move of this board. */
    fun play(move: Move): Board {
        val chessboard = board.copy()
        chessboard[move.from] = null
        chessboard[move.to] = if (move.isPromotion()) Queen(move.piece.player) else move.piece
        val counter = if (move.piece.type == PieceType.PAWN || move.isCapture()) 0 else captureOrPawnCounter + 1
        return Board(chessboard, turn.opponent(), counter)
    }

    private fun pseudoLegalMoves(): List<Move> = getPieces().flatMap { it.value.availableMovesOn(it.key, this) }

    /** A move is legal if it does not leave the own king in check. It is tested on this board and then undone. */
    private fun isLegal(move: Move): Boolean {
        board[move.from] = null
        board[move.to] = move.piece
        val legal = !isInCheck(turn)
        board[move.to] = move.captured
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

    /** Only kings, or kings and a single minor piece. */
    private fun hasInsufficientMaterial(): Boolean {
        val pieces = getPieces(Player.WHITE) + getPieces(Player.BLACK)
        val nonKings = pieces.filter { it.value.type != PieceType.KING }
        return nonKings.isEmpty() || (nonKings.size == 1 && nonKings[0].value.type in setOf(PieceType.KNIGHT, PieceType.BISHOP))
    }

    fun printStatus() = println(status.getDescription(turn))

    operator fun get(p: Position): Piece? = board[p]

    operator fun get(i: Int, j: Int): Piece? = board[i, j]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Board

        if (board != other.board) return false
        if (turn != other.turn) return false

        return true
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
