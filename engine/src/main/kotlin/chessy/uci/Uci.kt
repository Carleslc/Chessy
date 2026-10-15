package chessy.uci

import chessy.Version
import chessy.book.OpeningBook
import chessy.core.Move
import chessy.core.MoveGenerator
import chessy.core.Notation
import chessy.core.Perft
import chessy.core.Position
import chessy.eval.Evaluator
import chessy.eval.SpaceWeights
import chessy.search.ParallelSearch
import chessy.search.Ponderhit
import chessy.search.Bench
import chessy.search.GreedySearch
import chessy.search.Search
import chessy.search.SearchLimits
import chessy.search.Strength
import chessy.syzygy.Syzygy
import chessy.search.TranspositionTable
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Universal Chess Interface, the protocol between chess GUIs and engines (https://www.chessprogramming.org/UCI).
 *
 * Commands are read from the GUI and handled one by one. The search runs in another thread,
 * so `stop` and `isready` are answered while the engine is thinking.
 *
 * Extra commands for debugging (not UCI): `d` (display the position), `moves` (legal moves), `perft <depth>`,
 * `eval` (evaluation terms) and `bench [depth]` (fixed search to measure speed).
 */
class Uci(private val output: (String) -> Unit = ::send) {

    private var position = Position.startingPosition()

    private val moveOverhead = SpinOption("Move Overhead", default = 100, min = 0, max = 5000)
    /**
     * Playing style (and difficulty): the alpha-beta search with an evaluator ("Space", the constrictor, by default,
     * "Full", the strongest, or "Material"), or the greedy search ("Greedy", fixed depth and only material, the weakest).
     */
    private val style = ComboOption("Style", default = Evaluator.DEFAULT, values = Evaluator.all.keys.toList() + GREEDY)
    private val greedyDepth = SpinOption("Greedy Depth", default = 2, min = 1, max = GreedySearch.MAX_DEPTH)
    private val hash = SpinOption("Hash", default = ParallelSearch.DEFAULT_HASH_MB, min = 1, max = TranspositionTable.MAX_SIZE_MB) {
        alphaBeta.transpositionTable.resize(it)
    }
    private val threads = SpinOption("Threads", default = ParallelSearch.DEFAULT_THREADS, min = 1, max = ParallelSearch.MAX_THREADS) {
        alphaBeta.threads = it
    }
    /** Weights file of the Space and Full styles (empty: the tuned weights of the style). */
    private val weightsFile = StringOption("Weights") { file ->
        customWeights = if (file.isBlank()) null else SpaceWeights.load(File(file))
    }
    private var customWeights: SpaceWeights? = null
    /** Evaluator name and weights file of the evaluators of the search, to create them again when they change. */
    private var evaluatorSettings: Pair<String, SpaceWeights?> = Evaluator.DEFAULT to null
    /** Whether the GUI lets the engine think on the opponent's time (`go ponder`): the engine only declares it. */
    private val ponder = CheckOption("Ponder", default = false)
    /** Play at the Elo of UCI_Elo instead of the full strength of the style (see [Strength]). */
    private val limitStrength = CheckOption("UCI_LimitStrength", default = false)
    private val elo = SpinOption("UCI_Elo", default = 1500, min = Strength.MIN_ELO, max = Strength.MAX_ELO)
    /** Play the moves of the opening book while the position is in it (off by default, as testers of rating lists require). */
    private val ownBook = CheckOption("OwnBook", default = false)
    /** Polyglot book to use instead of the books of Chessy (empty: the books of Chessy). */
    private val bookFile = StringOption("Book File") { file ->
        customBook = if (file.isBlank()) null else OpeningBook.load(File(file))
    }
    private var customBook: OpeningBook? = null
    /** Only play this opening of the books of Chessy (and the whole book when it is over). */
    private val opening = ComboOption("Opening", default = ALL_OPENINGS, values = listOf(ALL_OPENINGS) + OpeningBook.openings)
    /** Chess960: castling is sent and received as the king taking its own rook. */
    private val chess960 = CheckOption("UCI_Chess960", default = false)
    /**
     * Folders of the Syzygy endgame tablebases, separated by the path separator (empty: no tablebases).
     * By default the tables downloaded in tools/syzygy, if any.
     */
    private val syzygyPath = StringOption("SyzygyPath", default = Syzygy.defaultPath() ?: "") { path ->
        alphaBeta.tablebases = if (path.isBlank()) null else Syzygy(path).also {
            require(!it.isEmpty) { "No Syzygy tables found in $path" }
            output("info string Syzygy: ${it.size} tables up to ${it.maxPieces} pieces")
        }
    }
    private val syzygyProbeDepth = SpinOption("SyzygyProbeDepth", default = 1, min = 1, max = 100) { alphaBeta.tablebaseProbeDepth = it }
    private val options = listOf<Option>(
        style, hash, threads, moveOverhead, ponder, limitStrength, elo, ownBook, bookFile, opening, chess960,
        syzygyPath, syzygyProbeDepth, greedyDepth, weightsFile,
    ).associateBy { it.name.lowercase() }

    private val books = mutableMapOf<String, OpeningBook>()

    /** A move of the opening book for the [position] (if OwnBook): the chosen opening first, then the whole book. */
    private fun bookMove(position: Position): Move? {
        if (!ownBook.value) return null
        customBook?.let { return it.pick(position) }
        val names = if (opening.value == ALL_OPENINGS) listOf(ALL_BOOK) else listOf(opening.value, ALL_BOOK)
        return names.firstNotNullOfOrNull { name -> books.getOrPut(name) { OpeningBook.resource(name) }.pick(position) }
    }

    private val alphaBeta = ParallelSearch(ParallelSearch.DEFAULT_THREADS, transpositionTable = TranspositionTable(ParallelSearch.DEFAULT_HASH_MB)).apply {
        tablebases = Syzygy.defaultPath()?.let(::Syzygy)
    }
    private val greedy = GreedySearch()

    /**
     * How the engine plays: the Style option (with Greedy Depth), or with UCI_LimitStrength the setting for UCI_Elo,
     * which uses the style for the levels it can reach by limiting its nodes (Space if the style is Greedy).
     */
    private val setting: Strength.Setting get() =
        if (limitStrength.value) Strength.settingFor(elo.value, if (style.value == GREEDY) Evaluator.DEFAULT else style.value)
        else Strength.Setting(style.value, greedyDepth.value)

    /** The search of the current [setting]. */
    private fun search(setting: Strength.Setting = this.setting): Search =
        if (setting.isGreedy) greedy.apply { depth = setting.greedyDepth }
        else alphaBeta.apply {
            evaluator(setting)
            // The strength levels are calibrated with one thread
            singleThreaded = limitStrength.value
        }

    /** The evaluator of the current [setting] (the greedy search only counts material). */
    private fun evaluator(setting: Strength.Setting = this.setting): Evaluator {
        val name = if (setting.isGreedy) "Material" else setting.style
        val settings = name to customWeights
        if (settings != evaluatorSettings) {
            evaluatorSettings = settings
            alphaBeta.evaluatorFactory = { Evaluator.named(settings.first, settings.second) }
        }
        return alphaBeta.evaluator
    }

    private val searchThread: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "search").apply { isDaemon = true }
    }
    private var searchTask: Future<*>? = null
    private val stop = AtomicBoolean()

    /** Released when the GUI allows to send the best move after `go infinite` or `go ponder`. */
    private var bestMoveAllowed = CountDownLatch(0)

    /** Signal of `ponderhit` for the current search. */
    private var ponderhit = Ponderhit()

    /** Reads and handles commands until `quit` or the end of the input. */
    fun run(input: BufferedReader) {
        while (true) {
            val line = input.readLine() ?: break
            if (!handle(line)) break
        }
        stopSearch()
        searchThread.shutdown()
    }

    /** Handles a command. Returns false when the engine must quit. */
    fun handle(line: String): Boolean {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        try {
            when (tokens[0]) {
                "uci" -> {
                    output("id name ${Version.name}")
                    output("id author ${Version.AUTHOR}")
                    options.values.forEach { output(it.toUci()) }
                    output("uciok")
                }
                "isready" -> output("readyok")
                "ucinewgame" -> {
                    stopSearch()
                    alphaBeta.clear()
                    greedy.clear()
                    position = Position.startingPosition()
                }
                "position" -> {
                    stopSearch()
                    setPosition(tokens)
                }
                "go" -> go(tokens)
                "stop" -> stopSearch()
                // The opponent played the expected move: the search goes on with the time of the engine
                "ponderhit" -> {
                    ponderhit.signal()
                    bestMoveAllowed.countDown()
                }
                "setoption" -> setOption(tokens)
                "debug", "register" -> {}
                "quit" -> {
                    stopSearch()
                    return false
                }
                "d" -> output(position.toString())
                "moves" -> output(MoveGenerator.legalMoves(position).toList().joinToString(" ") { Notation.toSan(position, it) })
                "perft" -> perft(tokens.getOrNull(1))
                "eval" -> output(evaluator().explain(position).toString())
                "bench" -> bench(tokens.getOrNull(1))
                else -> output("info string Unknown command: $line")
            }
        } catch (e: IllegalArgumentException) {
            output("info string Error: ${e.message}")
        }
        return true
    }

    /** Waits until the current search (if any) sends its best move. */
    fun waitForSearch() {
        searchTask?.get()
    }

    /** `position [startpos | fen <fen>] [moves <move1> ... <moveN>]`. Moves are applied until an illegal one. */
    private fun setPosition(tokens: List<String>) {
        val movesIndex = tokens.indexOf("moves").let { if (it < 0) tokens.size else it }
        val newPosition = when (tokens.getOrNull(1)) {
            "startpos" -> Position.startingPosition()
            "fen" -> Position.fromFen(tokens.subList(2, movesIndex).joinToString(" "))
            else -> throw IllegalArgumentException("Expected position startpos or position fen <fen>")
        }
        position = newPosition
        position.chess960 = chess960.value
        for (uci in tokens.drop(movesIndex + 1)) {
            position.makeMove(Notation.parseUci(position, uci))
        }
    }

    private fun go(tokens: List<String>) {
        stopSearch()
        if (tokens.getOrNull(1) == "perft") {
            perft(tokens.getOrNull(2))
            return
        }

        val setting = setting
        // A limited strength plays a number of nodes per move
        val limits = parseLimits(tokens).let { limits ->
            setting.nodes?.let { nodes -> limits.copy(nodes = minOf(limits.nodes ?: nodes, nodes)) } ?: limits
        }
        ponderhit = limits.ponderhit
        val searchPosition = position.copy()
        val search = search(setting)
        stop.set(false)
        val allowed = CountDownLatch(if (limits.infinite || limits.ponder) 1 else 0)
        bestMoveAllowed = allowed

        // The book is read here, with the options of this moment (later commands may change them while the search starts)
        val book = if (limits.infinite || limits.ponder || limits.searchMoves.isNotEmpty()) null else bookMove(searchPosition)

        searchTask = searchThread.submit {
            val legalMoves = MoveGenerator.legalMoves(searchPosition)
            var bestMove: Move? = null
            var ponderMove = Move.NONE
            try {
                if (book != null) {
                    output("info string book move")
                    bestMove = book
                } else if (!legalMoves.isEmpty()) {
                    val result = search.search(searchPosition, limits, stop) { info -> output(info.toUci(searchPosition.chess960)) }
                    bestMove = result.bestMove
                    ponderMove = result.ponderMove
                }
            } catch (e: Exception) {
                output("info string Search error: $e")
                // Never leave the GUI without a move
                bestMove = if (legalMoves.isEmpty()) null else legalMoves[0]
            }
            // With infinite or ponder, the best move can only be sent after stop or ponderhit
            allowed.await()
            output(when {
                bestMove == null -> "bestmove (none)"
                ponderMove != Move.NONE -> "bestmove ${bestMove.toUci(searchPosition.chess960)} ponder ${ponderMove.toUci(searchPosition.chess960)}"
                else -> "bestmove ${bestMove.toUci(searchPosition.chess960)}"
            })
        }
    }

    /** `go [wtime <ms>] [btime <ms>] [winc <ms>] [binc <ms>] [movestogo <n>] [depth <n>] [nodes <n>] [mate <n>] [movetime <ms>] [infinite] [ponder] [searchmoves <move1> ...]` */
    private fun parseLimits(tokens: List<String>): SearchLimits {
        var limits = SearchLimits(moveOverhead = moveOverhead.value.toLong())
        var i = 1
        fun number(): Long = tokens.getOrNull(++i)?.toLongOrNull()
            ?: throw IllegalArgumentException("Expected a number after ${tokens[i - 1]}")
        while (i < tokens.size) {
            limits = when (tokens[i]) {
                "wtime" -> limits.copy(whiteTime = number())
                "btime" -> limits.copy(blackTime = number())
                "winc" -> limits.copy(whiteIncrement = number())
                "binc" -> limits.copy(blackIncrement = number())
                "movestogo" -> limits.copy(movesToGo = number().toInt())
                "depth" -> limits.copy(depth = number().toInt())
                "nodes" -> limits.copy(nodes = number())
                "mate" -> limits.copy(mate = number().toInt())
                "movetime" -> limits.copy(moveTime = number())
                "infinite" -> limits.copy(infinite = true)
                "ponder" -> limits.copy(ponder = true)
                "searchmoves" -> {
                    val moves = mutableListOf<Move>()
                    while (i + 1 < tokens.size && tokens[i + 1] !in GO_KEYWORDS) {
                        moves.add(Notation.parseUci(position, tokens[++i]))
                    }
                    limits.copy(searchMoves = moves)
                }
                else -> throw IllegalArgumentException("Unknown go parameter: ${tokens[i]}")
            }
            i++
        }
        return limits
    }

    /** `setoption name <id> [value <x>]`. Option names are case insensitive. */
    private fun setOption(tokens: List<String>) {
        val valueIndex = tokens.indexOf("value").let { if (it < 0) tokens.size else it }
        val name = tokens.subList(2, valueIndex).joinToString(" ")
        val value = tokens.drop(valueIndex + 1).joinToString(" ")
        val option = options[name.lowercase()] ?: throw IllegalArgumentException("Unknown option: $name")
        option.set(value)
    }

    /** Sets the stop signal and waits until the search sends its best move. */
    private fun stopSearch() {
        stop.set(true)
        bestMoveAllowed.countDown()
        waitForSearch()
        searchTask = null
    }

    private fun perft(depth: String?) {
        val plies = depth?.toIntOrNull()?.takeIf { it >= 1 } ?: throw IllegalArgumentException("Expected perft <depth>")
        val start = System.currentTimeMillis()
        val divide = Perft.parallelDivide(position, plies, Runtime.getRuntime().availableProcessors())
        for ((move, nodes) in divide) output("$move: $nodes")
        output("")
        output("Nodes searched: ${divide.sumOf { it.second }} (${System.currentTimeMillis() - start} ms)")
    }

    private fun bench(depth: String?) {
        stopSearch()
        val plies = depth?.toIntOrNull() ?: Bench.DEFAULT_DEPTH
        val result = Bench.run(search(), plies, output)
        output("Nodes: ${result.nodes}")
        output("Time: ${result.millis} ms")
        output("NPS: ${result.nodesPerSecond}")
    }

    private interface Option {
        val name: String
        fun set(text: String)
        fun toUci(): String
    }

    /** Integer option in a range. */
    private class SpinOption(
        override val name: String, val default: Int, val min: Int, val max: Int, val onChange: (Int) -> Unit = {},
    ) : Option {
        var value = default
            private set

        override fun set(text: String) {
            val number = text.toIntOrNull() ?: throw IllegalArgumentException("$name must be a number")
            require(number in min..max) { "$name must be between $min and $max" }
            value = number
            onChange(number)
        }

        override fun toUci() = "option name $name type spin default $default min $min max $max"
    }

    /** Option with a list of possible values (case insensitive). */
    private class ComboOption(override val name: String, val default: String, val values: List<String>) : Option {
        var value = default
            private set

        override fun set(text: String) {
            value = values.firstOrNull { it.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("$name must be one of ${values.joinToString()}")
        }

        override fun toUci() = "option name $name type combo default $default " + values.joinToString(" ") { "var $it" }
    }

    /** Boolean option. */
    private class CheckOption(override val name: String, val default: Boolean) : Option {
        var value = default
            private set

        override fun set(text: String) {
            value = when (text.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$name must be true or false")
            }
        }

        override fun toUci() = "option name $name type check default $default"
    }

    /** Text option, such as a file name (empty by default). */
    private class StringOption(override val name: String, val default: String = "", val onChange: (String) -> Unit) : Option {
        override fun set(text: String) {
            val value = if (text == EMPTY) "" else text
            try {
                onChange(value)
            } catch (e: IOException) {
                throw IllegalArgumentException("$name: ${e.message}")
            }
        }

        override fun toUci() = "option name $name type string default ${default.ifEmpty { EMPTY }}"
    }

    companion object {
        private const val GREEDY = Strength.GREEDY
        private const val ALL_OPENINGS = "All"
        private const val ALL_BOOK = "book"

        /** Value of an empty string option in UCI. */
        private const val EMPTY = "<empty>"

        private val GO_KEYWORDS = setOf(
            "wtime", "btime", "winc", "binc", "movestogo", "depth", "nodes", "mate", "movetime", "infinite", "ponder", "searchmoves"
        )

        private fun send(line: String) {
            synchronized(System.out) {
                println(line)
                System.out.flush()
            }
        }
    }
}
