package core

import evaluation.Evaluator
import evaluation.WeightEvaluator
import pieces.*

class Board private constructor(private val board: Chessboard,
                                var turn: Player,
                                val evaluator: Evaluator,
                                private var captureOrPawnCounter: Int = 0,
                                private var playerStatus: Status = Status.PLAYING,
                                private var chessyStatus: Status = Status.PLAYING) {

    companion object Factory {

        fun new(): Board {
            val chessboard = ChessboardMap.empty()
            fillRow(chessboard, 0, Player.BLACK)
            fillPawns(chessboard, 1, Player.BLACK)
            // board[2][*] to board[5][*] are empty positions
            fillPawns(chessboard, 6, Player.WHITE)
            fillRow(chessboard, 7, Player.WHITE)
            val board = Board(chessboard, Player.WHITE, WeightEvaluator())
            board.calculateMoves()
            return board
        }

        private fun fillRow(board: Chessboard, row: Int, player: Player) {
            board[row, 0] = Rook(player)
            board[row, 1] = Knight(player)
            board[row, 2] = Bishop(player)
            board[row, 3] = Queen(player)
            board[row, 4] = King(player)
            board[row, 5] = Bishop(player)
            board[row, 6] = Knight(player)
            board[row, 7] = Rook(player)
        }

        private fun fillPawns(board: Chessboard, row: Int, player: Player) {
            for (column in 0..7) {
                board[row, column] = Pawn(player)
            }
        }

        private fun copy(board: Board): Board {
            with (board) {
                return Board(this.board.copy(), turn, evaluator, captureOrPawnCounter, playerStatus, chessyStatus)
            }
        }

    }

    var moves: Set<Move> = setOf()
        private set

    fun getScore() = evaluator.getScore(this)

    fun getStatus() = if (turn.isPlayer()) playerStatus else chessyStatus

    private fun setStatus(status: Status) = if (turn.isPlayer()) playerStatus = status else chessyStatus = status

    fun printStatus() = println(getStatus().getDescription(turn))

    fun isEndGame() = getStatus().isTerminal()

    fun getPieces(): Set<Map.Entry<Position, Piece>> = board.getPieces(turn)

    private fun calculateMoves() {
        moves = getPieces().flatMapTo(hashSetOf()) { it.value.availableMovesOn(it.key, this) }
    }

    private fun apply(move: Move): Board {
        return apply {
            with (move) {
                val original = board[from]
                board[from] = null
                val target = board[to]
                board[to] = original

                if (original?.type == PieceType.PAWN || !target.isEmpty()) {
                    captureOrPawnCounter = 0
                } else if (++captureOrPawnCounter == 50) {
                    setStatus(Status.NO_CAPTURE_NOR_PAWN_MOVEMENT_FIFTY_MOVES)
                }

                if (!isEndGame()) {
                    turn = turn.opponent()
                    calculateMoves()
                    if (moves.isEmpty()) {
                        setStatus(Status.STALEMATE)
                    }
                }
            }
        }
    }

    fun applied(move: Move): Board = copy().apply(move) // TODO: HashMap<Move, Board>

    fun isValid(m: Move, player: Player): Boolean {
        val piece = get(m.from)
        return piece.isOwn(player) && m in piece!!.availableMovesOn(m.from, this)
    }

    fun copy() = Factory.copy(this)

    operator fun get(p: Position): Piece? = board[p]

    operator fun get(i: Int, j: Int): Piece? = board[i, j]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Board

        if (board != other.board) return false

        return true
    }

    override fun hashCode() = board.hashCode()

    override fun toString(): String {
        val builder = StringBuilder()
        val columns = board.columns.joinToString(separator="    ", prefix="    ") { Position.getAlgebraicChessColumn(it).toString() }
        builder.appendln(columns)
        for (i in board.rows) {
            val p = 8 - i
            builder.append("$p ")
            builder.append(board[i].joinToString(prefix="[", postfix="]") { it?.toString() ?: "___" })
            builder.appendln(" $p")
        }
        builder.appendln(columns)
        return builder.toString()
    }

}