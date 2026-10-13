package core

import evaluation.WeightEvaluator
import search.AlphaBeta
import java.io.File

val EVALUATOR = WeightEvaluator()

val LAST = File("tests/last.board")

private const val USAGE = """Usage: chessy [options]
  --black          Chessy plays black (default: white)
  --auto           Chessy plays both sides
  --human          Human plays both sides
  --fen <fen>      Start from a position in FEN notation (between quotes)
  --board <file>   Start from a board file (see tests/test.board)
  --turn <color>   Player to move first with --board: white (default) or black
  --depth <n>      Maximum search depth (default: no limit, only time)
  --time <s>       Maximum seconds to think per move (default: 5)
  --pgn <file>     Save the game in PGN notation to this file"""

fun main(args: Array<String>) {
    var boardFile: String? = null
    var fen: String? = null
    var pgnFile: File? = null
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
                "--fen" -> fen = value()
                "--board" -> boardFile = value()
                "--turn" -> turn = Player.valueOf(value().toUpperCase())
                "--depth" -> search.maxDepth = value().toInt()
                "--time" -> search.maxMillis = (value().toDouble() * 1000).toLong()
                "--pgn" -> pgnFile = File(value())
                else -> throw IllegalArgumentException("Unknown option ${args[i]}")
            }
            i++
        }
        val board = when {
            fen != null -> Board.fromFen(fen!!)
            boardFile != null -> Board.parseFile(boardFile!!, turn)
            else -> Board.new()
        }
        play(board, search, pgnFile)
    } catch (e: IllegalArgumentException) {
        println(e.message)
        println(USAGE)
    }
}

fun play(startingBoard: Board, search: AlphaBeta, pgnFile: File?) {
    var board = startingBoard
    val history = mutableListOf<Long>() // Keys of the previous positions, to detect repetitions
    val pgn = Pgn(startingBoard, pgnName(Player.WHITE), pgnName(Player.BLACK))

    println("Game Started\n")
    println(board)

    while (true) {
        val repetitions = history.count { it == board.key }
        val status = if (repetitions >= 2) Status.THREEFOLD_REPETITION else board.status
        println(status.getDescription(board.turn))
        if (status.isTerminal()) {
            pgn.finish(board, status)
            break
        }
        if (board.turn == Player.WHITE) {
            save(board)
        }
        println("FEN: ${board.toFen()}")
        println("Legal Moves (${board.legalMoves.size}): ${board.legalMoves.joinToString { board.toSan(it) }}")
        println()

        val move = if (board.turn.isChessy()) playChessy(board, search, history) else playPlayer(board) ?: break
        println("${board.toSan(move)}  ($move)")
        println()

        pgn.add(board, move)
        history.add(board.key)
        board = board.play(move)
        println(board)
    }

    println()
    println(pgn)
    pgnFile?.let {
        it.absoluteFile.parentFile.mkdirs()
        it.writeText(pgn.toString())
        println("Game saved to ${it.path}")
    }
}

fun pgnName(player: Player) = if (player.isChessy()) "Chessy" else "Player"

fun save(board: Board) {
    if (!LAST.exists()) {
        LAST.absoluteFile.parentFile.mkdirs()
        LAST.createNewFile()
    }
    LAST.writeText(board.toString())
}

/** Reads a move such as "Nf3", "e4", "O-O", "e7e8q" or "e2e4". Returns null if the player quits. */
fun playPlayer(board: Board): Move? {
    println("${board.turn.getName()} TURN, Score: ${EVALUATOR.getScore(board)}\n")
    while (true) {
        print("What is your move? (e.g. e4, Nf3, O-O, e2e4, or quit): ")
        val input = readLine()?.trim() ?: return null
        if (input == "quit" || input == "exit") {
            return null
        }
        val move = board.parseMove(input)
        if (move != null) {
            return move
        }
        println("Impossible move")
    }
}

fun playChessy(board: Board, search: AlphaBeta, history: List<Long>): Move {
    println("${board.turn.getName()} TURN, Score: ${EVALUATOR.getScore(board)}\n")
    println("Thinking...")
    val start = System.currentTimeMillis()
    val best = search.search(board, history)
    println("Best: ${board.toSan(best.move)} (score ${best.score}) in ${System.currentTimeMillis() - start} ms")
    return best.move
}
