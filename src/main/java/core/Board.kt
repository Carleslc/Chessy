package core

import me.carleslc.kotlin.extensions.strings.splitLines
import pieces.*
import evaluation.KingTable
import evaluation.KingTableEndGame
import java.io.File
import java.util.*

private val movesCache = WeakHashMap<Board, Set<Move>>()
private val legalMovesCache = WeakHashMap<Board, List<Move>>()

private var deepCheck = true

class Board private constructor(val board: Chessboard,
                                var turn: Player,
                                private var captureOrPawnCounter: Byte = 0,
                                private var playerStatus: Status = Status.PLAYING,
                                private var chessyStatus: Status = Status.PLAYING) {

    companion object Factory {

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

        fun parse(board: String): Board {
            fun syntaxError(message: String) = IllegalArgumentException("Invalid board syntax: $message")
            fun syntaxError(e: Exception) = IllegalArgumentException("Invalid board syntax", e)

            val lines = board.splitLines()
            val chessboard = ChessboardMatrix.empty()

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
                    val piece = parsePiece(rawPiece.value)
                    chessboard[i][j++] = piece
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

            return with(chessboard)
        }

        fun parseFile(boardPath: String) = parse(File(boardPath).readText())

        private fun with(chessboard: Chessboard, player: Player = Player.Declarations.START): Board {
            val board = Board(chessboard, player)
            board.checkStatus()
            return board
        }

        private fun copy(board: Board): Board {
            with (board) {
                return Board(this.board.copy(), turn, captureOrPawnCounter, playerStatus, chessyStatus)
            }
        }

    }

    private val moves get(): Set<Move> {
        val moves = movesCache[this]
        if (moves != null) {
            //println("BOARD IN MOVES CACHE (${turn.getName()})")
            return moves
        }
        return (getPieces().flatMapTo(hashSetOf()) { it.value.availableMovesOn(it.key, this) }).also { movesCache[this] = it }
    }

    val legalMoves get(): List<Move> {
        val legalMoves = legalMovesCache[this]
        if (legalMoves != null) {
            return legalMoves
        }
        return moves.filter { isValid(it) }.sortedDescending().also { legalMovesCache[this] = it }
    }

    fun getStatus(player: Player = turn) = if (player.isPlayer()) playerStatus else chessyStatus

    private fun setStatus(status: Status, player: Player = turn) = if (player.isPlayer()) playerStatus = status else chessyStatus = status

    fun printStatus() {
        val status = getStatus()
        val opponentStatus = getStatus(turn.opponent())
        when {
            status.isTerminal() -> println(status.getDescription(turn))
            opponentStatus.isTerminal() -> println(opponentStatus.getDescription(turn.opponent()))
            else -> {
                println(turn.getName() + ": " + status.getDescription(turn))
                println(turn.opponent().getName() + ": " + opponentStatus.getDescription(turn.opponent()))
            }
        }
    }

    fun isEndGame() = getStatus().isTerminal() || getStatus(turn.opponent()).isTerminal()

    fun getPieces(player: Player = turn): Set<Map.Entry<Position, Piece>> = board.getPieces(player)

    private fun checkStatus() {
        turn = turn.opponent()
        --captureOrPawnCounter
        val empty = board.first { _, piece -> piece.isEmpty() }!!
        val pos = empty.key
        apply(Move(pos, pos, this))
    }

    private fun apply(move: Move): Board {
        return apply {
            with (move) {
                val ownPlayer = turn
                val opponentPlayer = turn.opponent()
                val original = board[from]
                val target = board[to]
                board[from] = null

                val pawn = original?.type == PieceType.PAWN
                val promotion = pawn && to.opponentRow(ownPlayer)
                board[to] = if (promotion) Queen(ownPlayer) else original

                if (pawn || !target.isEmpty()) {
                    captureOrPawnCounter = 0
                } else if (++captureOrPawnCounter == 50.toByte()) {
                    setStatus(Status.NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES, ownPlayer)
                    setStatus(Status.NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES, opponentPlayer)
                }

                if (!isEndGame()) {
                    val ownMoves = moves
                    val opponentCheck = ownMoves.any { it.target?.type == PieceType.KING }
                    turn = opponentPlayer
                    val opponentMoves = moves
                    if (opponentMoves.isEmpty()) {
                        setStatus(Status.STALEMATE, ownPlayer)
                        setStatus(Status.STALEMATE, opponentPlayer)
                    } else if (opponentCheck && allMovesInCheck(opponentMoves, opponentPlayer)) {
                        setStatus(Status.CHECKMATE, opponentPlayer)
                    } else {
                        val ownCheck = opponentMoves.any { it.target?.type == PieceType.KING }
                        setCheck(ownCheck, ownPlayer)
                        setCheck(opponentCheck, opponentPlayer)
                    }
                }
            }
        }
    }

    private fun setCheck(check: Boolean, player: Player) {
        if (check) {
            setStatus(Status.CHECK, player)
            PieceType.KNIGHT.positionWeightTable = KingTableEndGame
        } else {
            setStatus(Status.PLAYING, player)
            PieceType.KNIGHT.positionWeightTable = KingTable
        }
    }

    fun applied(move: Move) = copy().apply(move)

    private fun isValid(m: Move, player: Player = turn) = get(m.from).isOwn(player) && !isInCheck(m, player)

    private fun allMovesInCheck(moves: Set<Move>, player: Player): Boolean {
        if (!deepCheck) {
            return false
        }
        deepCheck = false
        val all = moves.all { isInCheck(it, player) }
        deepCheck = true
        return all
    }

    private fun isInCheck(m: Move, player: Player = turn): Boolean {
        val status = m.nextBoard.getStatus(player)
        val opponentStatus = m.nextBoard.getStatus(player.opponent())
        return status.isCheck() && !opponentStatus.isCheck()
    }

    fun copy() = Factory.copy(this)

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

    override fun hashCode() = 31 * board.hashCode() + turn.hashCode()

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