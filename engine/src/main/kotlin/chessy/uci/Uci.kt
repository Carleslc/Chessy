package chessy.uci

import chessy.Version
import chessy.core.Move
import chessy.core.MoveGenerator
import chessy.core.Notation
import chessy.core.Perft
import chessy.core.Position
import chessy.eval.Evaluator
import chessy.eval.SpaceEvaluator
import chessy.eval.SpaceWeights
import chessy.search.ParallelSearch
import chessy.search.Bench
import chessy.search.GreedySearch
import chessy.search.Search
import chessy.search.SearchLimits
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
     * or "Material"), or the greedy search ("Greedy", fixed depth and only material, the weakest).
     */
    private val style = ComboOption("Style", default = Evaluator.DEFAULT, values = Evaluator.all.keys.toList() + GREEDY)
    private val greedyDepth = SpinOption("Greedy Depth", default = 2, min = 1, max = GreedySearch.MAX_DEPTH)
    private val hash = SpinOption("Hash", default = TranspositionTable.DEFAULT_SIZE_MB, min = 1, max = TranspositionTable.MAX_SIZE_MB) {
        alphaBeta.transpositionTable.resize(it)
    }
    private val threads = SpinOption("Threads", default = 1, min = 1, max = ParallelSearch.MAX_THREADS) {
        alphaBeta.threads = it
    }
    /** Weights file of the Space style (empty: the tuned weights of the engine). */
    private val weightsFile = StringOption("Weights") { file ->
        spaceWeights = if (file.isBlank()) SpaceWeights.DEFAULT else SpaceWeights.load(File(file))
    }
    private var spaceWeights = SpaceWeights.DEFAULT
    private val options = listOf<Option>(style, hash, threads, moveOverhead, greedyDepth, weightsFile)
        .associateBy { it.name.lowercase() }

    private val alphaBeta = ParallelSearch()
    private val greedy = GreedySearch()

    /** The search selected with the Style option. */
    private val search: Search get() =
        if (style.value == GREEDY) greedy.apply { depth = greedyDepth.value }
        else alphaBeta.apply { evaluator() }

    /** The evaluator of the Style option (the greedy search only counts material). */
    private fun evaluator(): Evaluator {
        val name = if (style.value == GREEDY) "Material" else style.value
        val current = alphaBeta.evaluator
        if (current.name != name || (current is SpaceEvaluator && current.weights !== spaceWeights)) {
            val weights = spaceWeights
            alphaBeta.evaluatorFactory = { if (name == "Space") SpaceEvaluator(weights) else Evaluator.named(name) }
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
                // Pondering is not supported yet (no Ponder option): the move found so far is played
                "ponderhit" -> stopSearch()
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

        val limits = parseLimits(tokens)
        val searchPosition = position.copy()
        val search = search
        stop.set(false)
        val allowed = CountDownLatch(if (limits.infinite || limits.ponder) 1 else 0)
        bestMoveAllowed = allowed

        searchTask = searchThread.submit {
            val legalMoves = MoveGenerator.legalMoves(searchPosition)
            var bestMove: Move? = null
            var ponderMove = Move.NONE
            try {
                if (!legalMoves.isEmpty()) {
                    val result = search.search(searchPosition, limits, stop) { info -> output(info.toUci()) }
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
                ponderMove != Move.NONE -> "bestmove $bestMove ponder $ponderMove"
                else -> "bestmove $bestMove"
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
        val result = Bench.run(search, plies, output)
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

    /** Text option, such as a file name (empty by default). */
    private class StringOption(override val name: String, val onChange: (String) -> Unit) : Option {
        override fun set(text: String) {
            val value = if (text == EMPTY) "" else text
            try {
                onChange(value)
            } catch (e: IOException) {
                throw IllegalArgumentException("$name: ${e.message}")
            }
        }

        override fun toUci() = "option name $name type string default $EMPTY"
    }

    companion object {
        private const val GREEDY = "Greedy"

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
