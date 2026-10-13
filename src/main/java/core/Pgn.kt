package core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A game in Portable Game Notation, to analyze it later in other platforms (Lichess, Chess.com...).
 * Moves are written in standard algebraic notation.
 */
class Pgn(private val startingBoard: Board,
          private val white: String,
          private val black: String,
          private val event: String = "Chessy game") {

    private val moves = mutableListOf<String>()

    /** Result: 1-0, 0-1, 1/2-1/2 or * (unfinished). */
    var result = "*"

    /** Adds a legal [move] played on [board]. */
    fun add(board: Board, move: Move) {
        moves.add(board.toSan(move))
    }

    /** Sets the result from the final status of the game. */
    fun finish(board: Board, status: Status) {
        result = when {
            status == Status.CHECKMATE -> if (board.turn == Player.WHITE) "0-1" else "1-0"
            status.isDraw() -> "1/2-1/2"
            else -> "*"
        }
    }

    override fun toString(): String {
        val headers = linkedMapOf(
            "Event" to event,
            "Site" to "Chessy (kotlin-old)",
            "Date" to LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),
            "Round" to "-",
            "White" to white,
            "Black" to black,
            "Result" to result
        )
        val fen = startingBoard.toFen()
        if (fen != Board.STARTING_FEN) {
            headers["SetUp"] = "1"
            headers["FEN"] = fen
        }

        val tokens = mutableListOf<String>()
        var moveNumber = startingBoard.fullMoveNumber
        var whiteTurn = startingBoard.turn == Player.WHITE
        for ((i, san) in moves.withIndex()) {
            if (whiteTurn) {
                tokens.add("$moveNumber.")
            } else if (i == 0) {
                tokens.add("$moveNumber...")
            }
            tokens.add(san)
            if (!whiteTurn) moveNumber++
            whiteTurn = !whiteTurn
        }
        tokens.add(result)

        return headers.entries.joinToString("\n", postfix = "\n\n") { "[${it.key} \"${it.value}\"]" } + wrap(tokens) + "\n"
    }

    /** Lines of the movetext have at most 80 characters. */
    private fun wrap(tokens: List<String>): String {
        val lines = mutableListOf(StringBuilder())
        for (token in tokens) {
            val line = lines.last()
            if (line.isNotEmpty() && line.length + 1 + token.length > 80) {
                lines.add(StringBuilder(token))
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(token)
            }
        }
        return lines.joinToString("\n")
    }
}
