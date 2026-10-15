package snaky.core

/** How a game is going or how it ended. */
enum class GameStatus(val isDraw: Boolean = false) {
    ONGOING,
    CHECKMATE,
    STALEMATE(isDraw = true),
    INSUFFICIENT_MATERIAL(isDraw = true),
    /** 50 moves of each player without captures nor pawn moves. */
    FIFTY_MOVE_RULE(isDraw = true),
    /** The same position occurred three times. */
    THREEFOLD_REPETITION(isDraw = true);

    val isOver: Boolean get() = this != ONGOING

    companion object {
        /** Half moves without captures nor pawn moves to draw by the fifty-move rule. */
        const val FIFTY_MOVES = 100

        /**
         * Status of the [position]. The fifty-move rule and threefold repetition are draws that a player can claim:
         * engines and online platforms usually apply them directly, and so does Snaky.
         */
        fun of(position: Position): GameStatus = when {
            MoveGenerator.legalMoves(position).isEmpty() -> if (position.inCheck()) CHECKMATE else STALEMATE
            position.isInsufficientMaterial() -> INSUFFICIENT_MATERIAL
            position.halfmoveClock >= FIFTY_MOVES -> FIFTY_MOVE_RULE
            position.repetitions() >= 2 -> THREEFOLD_REPETITION
            else -> ONGOING
        }
    }
}

/**
 * A chess game: a starting position, the moves played and the PGN headers (tags).
 * See [Pgn] to export and import games.
 */
class Game(val startingFen: String = Position.STARTING_FEN, val chess960: Boolean = false) {

    /** Current position. Moves must be played with [play], not directly on the position. */
    val position: Position = Position.fromFen(startingFen).apply { chess960 = this@Game.chess960 }

    private val moves = mutableListOf<Move>()
    private val sanMoves = mutableListOf<String>()

    /** PGN tags, starting with the Seven Tag Roster. The Result tag is written from [result]. */
    val headers: MutableMap<String, String> = linkedMapOf(
        "Event" to "?",
        "Site" to "?",
        "Date" to "????.??.??",
        "Round" to "?",
        "White" to "?",
        "Black" to "?",
    )

    /**
     * Result of a game that did not end by the rules (resignation, time, agreement...):
     * `1-0`, `0-1`, `1/2-1/2`, or `*` while it is ongoing.
     */
    var declaredResult: String = UNFINISHED
        set(value) {
            require(value in RESULTS) { "Invalid result: $value" }
            field = value
        }

    val status: GameStatus get() = GameStatus.of(position)

    /** `1-0` (white wins), `0-1` (black wins), `1/2-1/2` (draw) or `*` (ongoing). */
    val result: String get() {
        val status = status
        return when {
            status == GameStatus.CHECKMATE -> if (position.sideToMove == WHITE) BLACK_WINS else WHITE_WINS
            status.isDraw -> DRAW
            else -> declaredResult
        }
    }

    fun moves(): List<Move> = moves.toList()

    fun sanMoves(): List<String> = sanMoves.toList()

    /** Plays a legal move. */
    fun play(move: Move) {
        checkNotOver()
        require(move in MoveGenerator.legalMoves(position)) { "Illegal move: $move" }
        sanMoves.add(Notation.toSan(position, move))
        moves.add(move)
        position.makeMove(move)
    }

    /** Plays a legal move in SAN (`Nf3`) or UCI (`g1f3`) notation, and returns it. */
    fun play(text: String): Move {
        checkNotOver()
        return Notation.parseMove(position, text).also { play(it) }
    }

    private fun checkNotOver() = check(!status.isOver) { "The game is over: $status" }

    /** Takes back the last move, if any. */
    fun undo(): Move? {
        if (moves.isEmpty()) return null
        val move = moves.removeAt(moves.lastIndex)
        sanMoves.removeAt(sanMoves.lastIndex)
        position.unmakeMove(move)
        return move
    }

    fun toPgn(): String = Pgn.write(this)

    companion object {
        const val WHITE_WINS = "1-0"
        const val BLACK_WINS = "0-1"
        const val DRAW = "1/2-1/2"
        const val UNFINISHED = "*"
        val RESULTS = setOf(WHITE_WINS, BLACK_WINS, DRAW, UNFINISHED)
    }
}
