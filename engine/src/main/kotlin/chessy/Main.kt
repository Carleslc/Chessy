package chessy

import chessy.core.Game
import chessy.core.MoveGenerator
import chessy.core.Notation
import chessy.core.Perft
import chessy.core.Pgn
import chessy.core.Position
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private const val USAGE = """Usage: chessy <command> [arguments] [options]

Commands:
  perft <depth> [fen]    Count the leaf nodes of the legal move tree (default: starting position)
  divide <depth> [fen]   Perft for each legal move of the position
  d [fen]                Display the position
  moves [fen]            Legal moves of the position, in SAN and UCI notation
  play                   Play a game in the console (moves in SAN or UCI notation)
  pgn <file>             Read the games of a PGN file and write them again (validates the moves)

Options:
  --threads <n>          Threads for perft and divide (default: available processors)
  --fen <fen>            Starting position of play (between quotes)
  --pgn <file>           Save the game played to this PGN file"""

/** Command line arguments: positional arguments and `--name value` options. */
private class Arguments(args: Array<String>) {
    val positional = mutableListOf<String>()
    val options = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < args.size) {
            if (args[i].startsWith("--")) {
                options[args[i].removePrefix("--")] = args.getOrNull(i + 1) ?: throw IllegalArgumentException("Missing value for ${args[i]}")
                i += 2
            } else {
                positional.add(args[i++])
            }
        }
    }

    val command get() = positional.firstOrNull()

    val depth: Int get() =
        positional.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Missing or invalid depth")

    /** The FEN is the rest of the positional arguments from [index], so it can be passed with or without quotes. */
    fun position(index: Int): Position =
        if (positional.size > index) Position.fromFen(positional.drop(index).joinToString(" ")) else Position.startingPosition()

    val threads: Int get() = options["threads"]?.toIntOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
}

fun main(args: Array<String>) {
    try {
        val arguments = Arguments(args)
        when (arguments.command) {
            null -> println(USAGE)
            "perft" -> perft(arguments.depth, arguments.position(2), arguments.threads)
            "divide" -> divide(arguments.depth, arguments.position(2), arguments.threads)
            "d" -> println(arguments.position(1))
            "moves" -> moves(arguments.position(1))
            "play" -> play(arguments.options["fen"] ?: Position.STARTING_FEN, arguments.options["pgn"]?.let(::File))
            "pgn" -> pgn(File(arguments.positional.getOrNull(1) ?: throw IllegalArgumentException("Missing PGN file")))
            else -> throw IllegalArgumentException("Unknown command: ${arguments.command}")
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(USAGE)
        exitProcess(1)
    }
}

private fun perft(depth: Int, position: Position, threads: Int) {
    val start = System.nanoTime()
    val nodes = if (threads > 1) Perft.parallelPerft(position, depth, threads) else Perft(position).perft(depth)
    printNodes(nodes, System.nanoTime() - start, threads)
}

private fun divide(depth: Int, position: Position, threads: Int) {
    val start = System.nanoTime()
    val results = if (threads > 1) Perft.parallelDivide(position, depth, threads) else Perft(position).divide(depth)
    for ((move, nodes) in results.sortedBy { it.first.toString() }) {
        println("$move: $nodes")
    }
    println()
    printNodes(results.sumOf { it.second }, System.nanoTime() - start, threads)
}

private fun printNodes(nodes: Long, nanos: Long, threads: Int) {
    val millis = nanos / 1_000_000
    val nps = if (nanos > 0) nodes * 1_000_000_000 / nanos else 0
    println("Nodes: $nodes")
    println("Time: $millis ms")
    println("NPS: $nps")
    println("Threads: $threads")
}

private fun moves(position: Position) {
    val moves = MoveGenerator.legalMoves(position).toList()
    println("${moves.size} legal moves")
    for (move in moves) {
        println("${Notation.toSan(position, move)} ($move)")
    }
}

/** Console game between two humans (Chessy will play when the search is implemented). */
private fun play(fen: String, pgnFile: File?) {
    val game = Game(fen)
    game.headers["Event"] = "Chessy game"
    game.headers["Site"] = "Chessy"
    game.headers["Date"] = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    game.headers["White"] = "Player"
    game.headers["Black"] = "Player"

    println("Moves in SAN (e4, Nf3, O-O, exd6, a8=Q) or UCI (e2e4). Commands: moves, undo, pgn, resign, draw, quit.")
    while (!game.status.isOver) {
        println()
        println(game.position)
        print("${if (game.position.sideToMove == chessy.core.WHITE) "White" else "Black"} to move: ")
        val input = readLine()?.trim() ?: break
        when (input) {
            "" -> continue
            "quit", "exit" -> break
            "moves" -> moves(game.position)
            "undo" -> game.undo()
            "pgn" -> println(game.toPgn())
            "resign" -> {
                game.declaredResult = if (game.position.sideToMove == chessy.core.WHITE) Game.BLACK_WINS else Game.WHITE_WINS
                break
            }
            "draw" -> {
                game.declaredResult = Game.DRAW
                break
            }
            else -> try {
                game.play(input)
            } catch (e: IllegalArgumentException) {
                println(e.message)
            }
        }
    }

    println()
    println(game.position)
    println("${game.status} ${game.result}")
    println()
    val pgn = game.toPgn()
    println(pgn)
    pgnFile?.let {
        it.absoluteFile.parentFile.mkdirs()
        it.writeText(pgn)
        println("Game saved to ${it.path}")
    }
}

private fun pgn(file: File) {
    val games = Pgn.readAll(file.readText())
    for (game in games) {
        println(game.toPgn())
        println("{ Final position: ${game.position.toFen()} (${game.status}) }")
        println()
    }
}
