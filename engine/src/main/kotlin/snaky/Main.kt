package snaky

import snaky.book.OpeningBook
import snaky.core.BLACK
import snaky.core.Color
import snaky.core.Game
import snaky.core.Move
import snaky.core.MoveGenerator
import snaky.core.Notation
import snaky.core.Perft
import snaky.core.Pgn
import snaky.core.Position
import snaky.core.WHITE
import snaky.eval.Evaluator
import snaky.eval.SpaceEvaluator
import snaky.eval.SpaceWeights
import snaky.search.ParallelSearch
import snaky.search.Bench
import snaky.search.GreedySearch
import snaky.search.Score
import snaky.search.Search
import snaky.search.SearchLimits
import snaky.search.Strength
import snaky.search.TranspositionTable
import snaky.syzygy.Syzygy
import snaky.tune.TexelTuner
import snaky.uci.Uci
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val USAGE = """Usage: snaky [command] [arguments] [options]

Without a command, Snaky speaks the UCI protocol (to be used from a chess GUI or lichess-bot).

Commands:
  perft <depth> [fen]    Count the leaf nodes of the legal move tree (default: starting position)
  divide <depth> [fen]   Perft for each legal move of the position
  d [fen]                Display the position
  moves [fen]            Legal moves of the position, in SAN and UCI notation
  eval [fen]             Evaluation of the position, term by term (--format line: in one line, in pawns)
  bench [depth]          Search fixed positions to a fixed depth (default: 12), to measure the speed
  play                   Play a game against Snaky in the console (moves in SAN or UCI notation)
  pgn <file>             Read the games of a PGN file and write them again (validates the moves)
  syzygy [fen]           Probe the Syzygy tablebases (--syzygy path): WDL and DTZ of the position, or of each FEN of the input
  weights                Weights of the Space style (or the Full style with --style full), in the format of a weights file
  tune <file>            Tune the weights of the Space style (or Full) with the positions of a training file (Texel's method)

Options:
  --threads <n>          Threads for perft and divide (default: available processors), bench (default: 1)
                         and play (default: 4, or the processors if fewer; 1 with --elo)
  --hash <mb>            Transposition table of bench (default: 64) and play (default: 256), in MB
  --style <name>         Playing style of eval, bench and play: space (default, the constrictor), full (the strongest),
                         material or greedy (the weakest)
  --depth <n>            Maximum depth of the search in play (fixed depth for greedy, default: 2)
  --elo <elo>            Play at this Elo (900 to 2800, in steps of 100) instead of the full strength of the style
  --book <on|off|file>   Opening book of play: the books of Snaky (default), none, or a Polyglot book file
  --opening <name>       Play this opening of the books of Snaky: KingsIndian, Benoni, French, CaroKann, Sicilian,
                         QueensGambit, RuyLopez or Pirc
  --chess960 <n>         Chess960: starting position number (0 to 959), random, or true (with --fen, and in divide and moves)
  --time <s>             Seconds to think per move in play (default: 3)
  --fen <fen>            Starting position of play (between quotes)
  --snaky <color>       Pieces played by Snaky in play: white, black (default), both or none
  --pgn <file>           Save the game played to this PGN file (added at the end if the file exists)
  --syzygy <path|off>    Folder of the Syzygy tablebases (default: tools/syzygy if downloaded), or off to play without them
  --weights <file>       Weights file of the Space or Full style (default: the tuned weights), also the starting point of tune
  --output <file>        Weights file written by tune (default: space.weights)
  --positions <n>        Maximum number of positions of the training file to tune
  --passes <n>           Maximum number of passes over the weights to tune (default: 100)
  --all <true|false>     Tune also the weights of the style (space, territory, restriction, mobility), not tuned by default
  --freeze <names>       Weights not tuned, separated by commas (a name without dot freezes the whole group, e.g. pieceValues)
  --only <names>         Tune only these weights, separated by commas (a name without dot tunes the whole group, e.g. scale)
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
        (if (positional.size > index) Position.fromFen(positional.drop(index).joinToString(" ")) else Position.startingPosition())
            .apply { chess960 = isChess960 }

    /** --chess960 true, a number or random: Chess960, with castling in UCI as the king taking its rook. */
    val isChess960: Boolean get() = options["chess960"]?.let { it != "false" } ?: false

    /** Starting position of play: --fen, or the Chess960 position of --chess960 (a number from 0 to 959 or random). */
    val startingFen: String get() {
        options["fen"]?.let { return it }
        return when (val chess960 = options["chess960"]) {
            null, "false", "true" -> Position.STARTING_FEN
            "random" -> Position.chess960Fen(kotlin.random.Random.nextInt(960))
            else -> Position.chess960Fen(chess960.toIntOrNull() ?: throw IllegalArgumentException("--chess960 must be true, random or a number from 0 to 959"))
        }
    }

    /** Opening book of play: the books of Snaky (--opening to play only one), a Polyglot --book file, or none with --book off. */
    val book: ((Position) -> Move?)? get() {
        val option = options["book"] ?: "on"
        if (option == "off" || option == "false") return null
        if (option != "on" && option != "true") return OpeningBook.load(File(option))::pick
        val all = OpeningBook.resource("book")
        val opening = options["opening"]?.let { name ->
            val found = OpeningBook.openings.firstOrNull { it.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown opening: $name (${OpeningBook.openings.joinToString()})")
            OpeningBook.resource(found)
        }
        return { position -> opening?.pick(position) ?: all.pick(position) }
    }

    val threads: Int get() = options["threads"]?.toIntOrNull()?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()

    /** The --style, with its name (Space, Full, Material or Greedy). */
    val style: String get() {
        val name = options["style"] ?: return Evaluator.DEFAULT
        return (Evaluator.all.keys + Strength.GREEDY).firstOrNull { it.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown style: $name (space, full, material or greedy)")
    }

    private val isGreedy: Boolean get() = style == Strength.GREEDY

    /** How Snaky plays: the --style (with --depth for greedy), or the setting of --elo (with the --style, Space by default). */
    val setting: Strength.Setting get() = when (val elo = intOption("elo")) {
        null -> Strength.Setting(style, intOption("depth") ?: 2)
        else -> Strength.settingFor(elo, if (isGreedy) Evaluator.DEFAULT else style)
    }

    /** Evaluator of the --style (the greedy search only counts material), with the --weights file if any. */
    val evaluator: Evaluator get() = evaluator(Strength.Setting(style))

    /** Evaluator of a [setting] (the greedy search only counts material), with the --weights file if any. */
    fun evaluator(setting: Strength.Setting): Evaluator =
        if (setting.isGreedy) Evaluator.named("Material") else Evaluator.named(setting.style, options["weights"]?.let { SpaceWeights.load(File(it)) })

    /** Weights of the Space or Full style: --weights file or the tuned weights of the style. */
    val weights: SpaceWeights get() =
        (evaluator as? SpaceEvaluator)?.weights ?: throw IllegalArgumentException("The $style style has no weights (only space and full)")

    fun intOption(name: String): Int? = options[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("--$name must be a number") }

    /**
     * Search of the [setting], with --threads and --hash: by default 1 thread for bench (to measure the speed of one thread)
     * and the threads and hash of the engine for play, which plays with one thread with --elo (as the levels were calibrated).
     */
    fun search(play: Boolean): Search {
        val setting = setting
        if (setting.isGreedy) return GreedySearch(setting.greedyDepth)
        val hash = intOption("hash") ?: if (play) ParallelSearch.DEFAULT_HASH_MB else TranspositionTable.DEFAULT_SIZE_MB
        require(hash in 1..TranspositionTable.MAX_SIZE_MB) { "--hash must be between 1 and ${TranspositionTable.MAX_SIZE_MB}" }
        val threads = intOption("threads") ?: if (play) ParallelSearch.DEFAULT_THREADS else 1
        return ParallelSearch(threads, { evaluator(setting) }, TranspositionTable(hash)).apply {
            singleThreaded = play && options["elo"] != null
            // The tables downloaded in tools/syzygy by default, --syzygy off to play without them
            tablebases = when (val path = options["syzygy"]) {
                "off", "false" -> null
                null -> Syzygy.defaultPath()?.let(::Syzygy)
                else -> Syzygy(path)
            }
        }
    }

    /** Limits of each move in play: --time seconds, --depth and the nodes of the [setting] (--elo). */
    val limits: SearchLimits get() = SearchLimits(
        moveTime = ((options["time"]?.toDoubleOrNull() ?: 3.0) * 1000).toLong(),
        depth = if (isGreedy || options["elo"] != null) null else intOption("depth"),
        nodes = setting.nodes,
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
            "eval" -> arguments.evaluator.explain(arguments.position(1)).let { println(if (arguments.options["format"] == "line") it.line() else it) }
            "bench" -> bench(arguments.search(play = false), arguments.positional.getOrNull(1)?.toIntOrNull() ?: Bench.DEFAULT_DEPTH)
            "play" -> play(arguments)
            "pgn" -> pgn(File(arguments.positional.getOrNull(1) ?: throw IllegalArgumentException("Missing PGN file")))
            "syzygy" -> syzygy(arguments)
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
    for ((move, nodes) in results.map { (move, nodes) -> Notation.toUci(position, move) to nodes }.sortedBy { it.first }) {
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
        println("${Notation.toSan(position, move)} (${Notation.toUci(position, move)})")
    }
}

private fun snakyColors(option: String?): Set<Color> = when (option ?: "black") {
    "white" -> setOf(WHITE)
    "black" -> setOf(BLACK)
    "both" -> setOf(WHITE, BLACK)
    "none" -> emptySet()
    else -> throw IllegalArgumentException("Invalid --snaky: $option (white, black, both or none)")
}

private fun bench(search: Search, depth: Int) {
    val result = Bench.run(search, depth, ::println)
    println()
    println("Nodes: ${result.nodes}")
    println("Time: ${result.millis} ms")
    println("NPS: ${result.nodesPerSecond}")
}

/** Console game against Snaky (or between humans, or Snaky against itself). */
private fun play(arguments: Arguments) {
    val snaky = snakyColors(arguments.options["snaky"])
    val setting = arguments.setting
    val search = arguments.search(play = true)
    val limits = arguments.limits
    val pgnFile = arguments.options["pgn"]?.let(::File)
    val threads = (search as? ParallelSearch)?.let { if (it.singleThreaded) 1 else it.threads } ?: 1
    val elo = arguments.intOption("elo")?.let(Strength::roundElo) ?: Strength.estimatedElo(setting, threads)
    val snakyName = "${Version.name} ($setting)"

    val book = arguments.book
    val game = Game(arguments.startingFen, arguments.isChess960)
    game.headers["Event"] = "Snaky game"
    game.headers["Site"] = "Snaky (v${Version.number})"
    game.headers["Date"] = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    game.headers["White"] = if (WHITE in snaky) snakyName else "Player"
    game.headers["Black"] = if (BLACK in snaky) snakyName else "Player"
    // Estimated Elo of Snaky, "-" for the player (unknown)
    game.headers["WhiteElo"] = if (WHITE in snaky) "$elo" else "-"
    game.headers["BlackElo"] = if (BLACK in snaky) "$elo" else "-"

    println("$snakyName, estimated Elo $elo (Stockfish scale, see the README)")
    val tablebases = (search as? ParallelSearch)?.tablebases
    println("Opening book: ${if (book == null) "off" else arguments.options["opening"] ?: arguments.options["book"]?.takeIf { it != "on" } ?: "on"}" +
        ", endgame tablebases: ${if (tablebases == null) "off" else "${tablebases.size} tables up to ${tablebases.maxPieces} pieces"}" +
        if (game.chess960) ", Chess960" else "")
    if (search is ParallelSearch) {
        println("Threads: $threads, hash: ${search.transpositionTable.entries.toLong() * TranspositionTable.ENTRY_BYTES / (1024 * 1024)} MB")
    }
    println("Moves in SAN (e4, Nf3, O-O, exd6, a8=Q) or UCI (e2e4). Commands: moves, eval, undo, pgn, resign, draw, quit.")
    println("Evaluations are in pawns from the point of view of White: positive is better for White, negative for Black.")
    while (!game.status.isOver) {
        println()
        println(game.position)
        val side = if (game.position.sideToMove == WHITE) "White" else "Black"
        if (game.position.sideToMove in snaky) {
            val bookMove = book?.invoke(game.position)
            if (bookMove != null) {
                println("$side (Snaky) plays ${Notation.toSan(game.position, bookMove)} (book)")
                game.play(bookMove)
                continue
            }
            val result = search.search(game.position, limits, AtomicBoolean())
            val evaluation = evaluationForWhite(result.score, game.position.sideToMove)
            println("$side (Snaky) plays ${Notation.toSan(game.position, result.bestMove)}, evaluation $evaluation")
            game.play(result.bestMove)
            continue
        }
        print("$side to move: ")
        val input = readLine()?.trim() ?: break
        when (input) {
            "" -> continue
            "quit", "exit" -> break
            "moves" -> moves(game.position)
            "eval" -> {
                // Static evaluation of the position, term by term (the greedy search only counts material)
                println(arguments.evaluator(setting).explain(game.position))
            }
            "undo" -> {
                // Take back the last move of the player, and the reply of Snaky
                game.undo()
                while (game.moves().isNotEmpty() && game.position.sideToMove in snaky) game.undo()
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
        val added = it.exists() && it.length() > 0
        Pgn.save(it, game)
        println(if (added) "Game added to ${it.path}" else "Game saved to ${it.path}")
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
    val only = arguments.options["only"]?.split(',')?.map { it.trim() }
    val names = SpaceWeights.PARAMETERS.map { it.name }
        .filter { tuneStyle || it !in SpaceWeights.STYLE }
        .filter { name -> frozen.none { name == it || name.startsWith("$it.") } }
        .filter { name -> only == null || only.any { name == it || name.startsWith("$it.") } }
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

/**
 * A search [score] (from the point of view of the [sideToMove]) for people: in pawns from the point of view of White,
 * such as "+0.35 (White is better)", "-2.40 (Black is better)" or "mate in 3 for Black".
 */
private fun evaluationForWhite(score: Int, sideToMove: Color): String {
    val white = if (sideToMove == WHITE) score else -score
    if (Score.isMate(white)) {
        val moves = (Score.MATE - Math.abs(white) + 1) / 2
        return "mate in $moves for ${if (white > 0) "White" else "Black"}"
    }
    val pawns = String.format(java.util.Locale.ROOT, "%+.2f", white / 100.0)
    return when {
        white > 0 -> "$pawns (White is better)"
        white < 0 -> "$pawns (Black is better)"
        else -> "$pawns (equal)"
    }
}

/** WDL and DTZ of a FEN (or of each FEN of the standard input) in the Syzygy tablebases. */
private fun syzygy(arguments: Arguments) {
    val tablebases = Syzygy(arguments.options["syzygy"] ?: Syzygy.defaultPath() ?: throw IllegalArgumentException("Missing --syzygy path"))
    require(!tablebases.isEmpty) { "No Syzygy tables found" }
    val fens = if (arguments.positional.size > 1) sequenceOf(arguments.positional.drop(1).joinToString(" "))
        else generateSequence(::readLine).filter { it.isNotBlank() }
    val position = Position.startingPosition()
    for (fen in fens) {
        position.setFen(fen)
        println("$fen;${tablebases.probeWdl(position) ?: "-"};${tablebases.probeDtz(position) ?: "-"}")
    }
}
