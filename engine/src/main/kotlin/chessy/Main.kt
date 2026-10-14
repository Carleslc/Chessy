package chessy

import chessy.core.BLACK
import chessy.core.Color
import chessy.core.Game
import chessy.core.MoveGenerator
import chessy.core.Notation
import chessy.core.Perft
import chessy.core.Pgn
import chessy.core.Position
import chessy.core.WHITE
import chessy.eval.Evaluator
import chessy.eval.SpaceEvaluator
import chessy.eval.SpaceWeights
import chessy.search.ParallelSearch
import chessy.search.Bench
import chessy.search.GreedySearch
import chessy.search.Score
import chessy.search.Search
import chessy.search.SearchLimits
import chessy.tune.TexelTuner
import chessy.uci.Uci
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val USAGE = """Usage: chessy [command] [arguments] [options]

Without a command, Chessy speaks the UCI protocol (to be used from a chess GUI or lichess-bot).

Commands:
  perft <depth> [fen]    Count the leaf nodes of the legal move tree (default: starting position)
  divide <depth> [fen]   Perft for each legal move of the position
  d [fen]                Display the position
  moves [fen]            Legal moves of the position, in SAN and UCI notation
  eval [fen]             Evaluation of the position, term by term
  bench [depth]          Search fixed positions to a fixed depth (default: 12), to measure the speed
  play                   Play a game against Chessy in the console (moves in SAN or UCI notation)
  pgn <file>             Read the games of a PGN file and write them again (validates the moves)
  weights                Weights of the Space style (or the Full style with --style full), in the format of a weights file
  tune <file>            Tune the weights of the Space style (or Full) with the positions of a training file (Texel's method)

Options:
  --threads <n>          Threads for perft and divide (default: available processors), and for bench and play (default: 1)
  --style <name>         Playing style of eval, bench and play: space (default, the constrictor), full (the strongest),
                         material or greedy (the weakest)
  --depth <n>            Maximum depth of the search in play (fixed depth for greedy, default: 2)
  --time <s>             Seconds to think per move in play (default: 3)
  --fen <fen>            Starting position of play (between quotes)
  --chessy <color>       Pieces played by Chessy in play: white, black (default), both or none
  --pgn <file>           Save the game played to this PGN file
  --weights <file>       Weights file of the Space or Full style (default: the tuned weights), also the starting point of tune
  --output <file>        Weights file written by tune (default: space.weights)
  --positions <n>        Maximum number of positions of the training file to tune
  --passes <n>           Maximum number of passes over the weights to tune (default: 100)
  --all <true|false>     Tune also the weights of the style (space, territory, restriction, mobility), not tuned by default
  --freeze <names>       Weights not tuned, separated by commas (a name without dot freezes the whole group, e.g. pieceValues)
  --max-change <cp>      Each tuned weight stays within its start value ± max(cp, |start value|)"""

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

    val style: String get() = options["style"]?.lowercase() ?: Evaluator.DEFAULT.lowercase()

    private val isGreedy: Boolean get() = style == "greedy"

    /** Evaluator of the --style (the greedy search only counts material), with the --weights file if any. */
    val evaluator: Evaluator get() =
        if (isGreedy) Evaluator.named("Material") else Evaluator.named(style, options["weights"]?.let { SpaceWeights.load(File(it)) })

    /** Weights of the Space or Full style: --weights file or the tuned weights of the style. */
    val weights: SpaceWeights get() =
        (evaluator as? SpaceEvaluator)?.weights ?: throw IllegalArgumentException("The $style style has no weights (only space and full)")

    fun intOption(name: String): Int? = options[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("--$name must be a number") }

    /** Search of the --style, with --threads and --depth. */
    val search: Search get() =
        if (isGreedy) GreedySearch(intOption("depth") ?: 2)
        else ParallelSearch(threads = intOption("threads") ?: 1, evaluatorFactory = { evaluator })

    /** Limits of each move in play: --time seconds and --depth. */
    val limits: SearchLimits get() = SearchLimits(
        moveTime = ((options["time"]?.toDoubleOrNull() ?: 3.0) * 1000).toLong(),
        depth = if (isGreedy) null else intOption("depth"),
    )
}

fun main(args: Array<String>) {
    try {
        val arguments = Arguments(args)
        when (arguments.command) {
            null -> {
                println("${Version.name} by ${Version.AUTHOR}")
                Uci().run(System.`in`.bufferedReader())
            }
            "help", "-h", "--help" -> println(USAGE)
            "perft" -> perft(arguments.depth, arguments.position(2), arguments.threads)
            "divide" -> divide(arguments.depth, arguments.position(2), arguments.threads)
            "d" -> println(arguments.position(1))
            "moves" -> moves(arguments.position(1))
            "eval" -> println(arguments.evaluator.explain(arguments.position(1)))
            "bench" -> bench(arguments.search, arguments.positional.getOrNull(1)?.toIntOrNull() ?: Bench.DEFAULT_DEPTH)
            "play" -> play(
                arguments.options["fen"] ?: Position.STARTING_FEN, chessyColors(arguments.options["chessy"]),
                arguments.search, arguments.limits, arguments.options["pgn"]?.let(::File)
            )
            "pgn" -> pgn(File(arguments.positional.getOrNull(1) ?: throw IllegalArgumentException("Missing PGN file")))
            "weights" -> print(arguments.weights.format())
            "tune" -> tune(arguments)
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

private fun chessyColors(option: String?): Set<Color> = when (option ?: "black") {
    "white" -> setOf(WHITE)
    "black" -> setOf(BLACK)
    "both" -> setOf(WHITE, BLACK)
    "none" -> emptySet()
    else -> throw IllegalArgumentException("Invalid --chessy: $option (white, black, both or none)")
}

private fun bench(search: Search, depth: Int) {
    val result = Bench.run(search, depth, ::println)
    println()
    println("Nodes: ${result.nodes}")
    println("Time: ${result.millis} ms")
    println("NPS: ${result.nodesPerSecond}")
}

/** Console game against Chessy (or between humans, or Chessy against itself). */
private fun play(fen: String, chessy: Set<Color>, search: Search, limits: SearchLimits, pgnFile: File?) {
    val game = Game(fen)
    game.headers["Event"] = "Chessy game"
    game.headers["Site"] = "Chessy (v${Version.number})"
    game.headers["Date"] = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    game.headers["White"] = if (WHITE in chessy) Version.name else "Player"
    game.headers["Black"] = if (BLACK in chessy) Version.name else "Player"

    println("Moves in SAN (e4, Nf3, O-O, exd6, a8=Q) or UCI (e2e4). Commands: moves, undo, pgn, resign, draw, quit.")
    while (!game.status.isOver) {
        println()
        println(game.position)
        val side = if (game.position.sideToMove == WHITE) "White" else "Black"
        if (game.position.sideToMove in chessy) {
            val result = search.search(game.position, limits, AtomicBoolean())
            println("$side (Chessy) plays ${Notation.toSan(game.position, result.bestMove)} (${Score.toUci(result.score)})")
            game.play(result.bestMove)
            continue
        }
        print("$side to move: ")
        val input = readLine()?.trim() ?: break
        when (input) {
            "" -> continue
            "quit", "exit" -> break
            "moves" -> moves(game.position)
            "undo" -> {
                // Take back the last move of the player, and the reply of Chessy
                game.undo()
                while (game.moves().isNotEmpty() && game.position.sideToMove in chessy) game.undo()
            }
            "pgn" -> println(game.toPgn())
            "resign" -> {
                game.declaredResult = if (game.position.sideToMove == WHITE) Game.BLACK_WINS else Game.WHITE_WINS
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

/** Texel tuning of the weights of the Space style, saved to --output after every pass. */
private fun tune(arguments: Arguments) {
    val file = File(arguments.positional.getOrNull(1) ?: throw IllegalArgumentException("Missing training file"))
    val output = File(arguments.options["output"] ?: "space.weights")
    val positions = TexelTuner.read(file, arguments.intOption("positions") ?: Int.MAX_VALUE)
    val tuneStyle = arguments.options["all"]?.toBoolean() ?: false
    val frozen = arguments.options["freeze"]?.split(',')?.map { it.trim() }.orEmpty()
    val names = SpaceWeights.PARAMETERS.map { it.name }
        .filter { tuneStyle || it !in SpaceWeights.STYLE }
        .filter { name -> frozen.none { name == it || name.startsWith("$it.") } }
    val tuner = TexelTuner(positions, arguments.threads)
    try {
        val start = arguments.weights
        val k = tuner.fitK(start)
        val tuned = tuner.tune(start, names, k, arguments.intOption("passes") ?: 100, arguments.intOption("max-change")) { it.save(output) }
        tuned.save(output)
        println("Saved $output")
    } finally {
        tuner.close()
    }
}
