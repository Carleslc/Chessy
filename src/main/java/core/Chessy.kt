package core

import evaluation.WeightEvaluator
import exceptions.NotationException
import search.AlphaBeta
import java.io.File

val EVALUATOR = WeightEvaluator()

val LAST = File("tests/last.board")

private const val USAGE = """Usage: chessy [options]
  --black          Chessy plays black (default: white)
  --auto           Chessy plays both sides
  --human          Human plays both sides
  --board <file>   Start from a board file (see tests/test.board)
  --turn <color>   Player to move first with --board: white (default) or black
  --depth <n>      Maximum search depth (default: 4)
  --time <s>       Maximum seconds to think per move (default: 10)"""

fun main(args: Array<String>) {
    var boardFile: String? = null
    var turn = Player.Declarations.START
    val search = AlphaBeta()

    var i = 0
    fun value() = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for ${args[i - 1]}")
    try {
        while (i < args.size) {
            when (args[i]) {
                "--black" -> Player.Declarations.CHESSY.apply { clear(); add(Player.BLACK) }
                "--auto" -> Player.Declarations.CHESSY.addAll(Player.values())
                "--human" -> Player.Declarations.CHESSY.clear()
                "--board" -> boardFile = value()
                "--turn" -> turn = Player.valueOf(value().toUpperCase())
                "--depth" -> search.maxDepth = value().toInt()
                "--time" -> search.maxMillis = (value().toDouble() * 1000).toLong()
                else -> throw IllegalArgumentException("Unknown option ${args[i]}")
            }
            i++
        }
    } catch (e: IllegalArgumentException) {
        println(e.message)
        println(USAGE)
        return
    }

    var board = boardFile?.let { Board.parseFile(it, turn) } ?: Board.new()
    val history = mutableListOf<Long>() // Keys of the previous positions, to detect repetitions

    println("Game Started\n")
    println(board)

    while (true) {
        val repetitions = history.count { it == board.key }
        val status = if (repetitions >= 2) Status.THREEFOLD_REPETITION else board.status
        println(status.getDescription(board.turn))
        if (status.isTerminal()) {
            break
        }
        if (board.turn == Player.WHITE) {
            save(board)
        }
        println("Legal Moves (${board.legalMoves.size}): ${board.legalMoves}")
        println()

        val move = if (board.turn.isChessy()) playChessy(board, search, history) else playPlayer(board) ?: return
        println(move)
        println()

        history.add(board.key)
        board = board.play(move)
        println(board)
    }
}

fun save(board: Board) {
    if (!LAST.exists()) {
        LAST.absoluteFile.parentFile.mkdirs()
        LAST.createNewFile()
    }
    LAST.writeText(board.toString())
}

/** Reads a move such as "e2e4" or "e2 e4". Returns null if the player quits. */
fun playPlayer(board: Board): Move? {
    println("${board.turn.getName()} TURN, Score: ${EVALUATOR.getScore(board)}\n")
    while (true) {
        print("What is your move? (e.g. e2e4, or quit): ")
        val input = readLine()?.replace(" ", "")?.toLowerCase() ?: return null
        if (input == "quit" || input == "exit") {
            return null
        }
        try {
            if (input.length != 4) {
                throw NotationException()
            }
            val from = Position.fromAlgebraicChessNotation(input.substring(0, 2))
            val to = Position.fromAlgebraicChessNotation(input.substring(2, 4))
            val move = board.legalMoves.find { it.from == from && it.to == to }
            if (move != null) {
                return move
            }
            println("Impossible move")
        } catch (e: NotationException) {
            println(e.message)
        }
    }
}

fun playChessy(board: Board, search: AlphaBeta, history: List<Long>): Move {
    println("${board.turn.getName()} TURN, Score: ${EVALUATOR.getScore(board)}\n")
    println("Thinking...")
    val start = System.currentTimeMillis()
    val best = search.search(board, history)
    println("Best: $best in ${System.currentTimeMillis() - start} ms")
    return best.move
}
