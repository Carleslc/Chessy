package chessy.search

import chessy.core.Position
import chessy.eval.Evaluator
import chessy.syzygy.Syzygy
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazy SMP (https://www.chessprogramming.org/Lazy_SMP): several threads search the same position at the same time,
 * sharing only the transposition table. Helper threads fill the table with results that the main thread reuses,
 * so it reaches deeper in the same time. Half of the helpers search one ply deeper, so they explore other parts of the tree.
 *
 * The main thread decides the move and the time; helpers stop when it finishes.
 * Each thread has its own evaluator instance (created with [evaluatorFactory]), position, killers and history.
 */
class ParallelSearch(
    threads: Int = 1,
    evaluatorFactory: () -> Evaluator = { Evaluator.named(Evaluator.DEFAULT) },
    val transpositionTable: TranspositionTable = TranspositionTable(),
) : Search {

    private var workers: List<AlphaBetaSearch> = emptyList()
    private var pool: ExecutorService? = null

    var evaluatorFactory: () -> Evaluator = evaluatorFactory
        set(value) {
            field = value
            workers.forEach { it.evaluator = value() }
        }

    var threads: Int = threads
        set(value) {
            require(value in 1..MAX_THREADS) { "Threads must be between 1 and $MAX_THREADS" }
            field = value
            createWorkers()
        }

    /** Syzygy tablebases of every thread (see [AlphaBetaSearch.tablebases]). */
    var tablebases: Syzygy? = null
        set(value) {
            field = value
            workers.forEach { it.tablebases = value }
        }

    var tablebaseProbeDepth: Int = 1
        set(value) {
            field = value
            workers.forEach { it.tablebaseProbeDepth = value }
        }

    /** Evaluator of the main thread. */
    val evaluator: Evaluator get() = workers[0].evaluator

    init {
        require(threads in 1..MAX_THREADS) { "Threads must be between 1 and $MAX_THREADS" }
        createWorkers()
    }

    private fun createWorkers() {
        pool?.shutdownNow()
        workers = List(threads) { index ->
            AlphaBetaSearch(evaluatorFactory(), transpositionTable).apply {
                isHelper = index > 0
                depthOffset = index % 2
                tablebases = this@ParallelSearch.tablebases
                tablebaseProbeDepth = this@ParallelSearch.tablebaseProbeDepth
            }
        }
        pool = if (threads > 1) {
            Executors.newFixedThreadPool(threads - 1) { runnable -> Thread(runnable, "search-helper").apply { isDaemon = true } }
        } else null
    }

    override fun search(position: Position, limits: SearchLimits, stop: AtomicBoolean, listener: (SearchInfo) -> Unit): SearchResult {
        val main = workers[0]
        val helpers = workers.drop(1)
        val helpersStop = AtomicBoolean(false)
        // Helpers search until the main thread finishes, each on its own copy of the position
        val helperLimits = SearchLimits(depth = limits.depth, infinite = true, searchMoves = limits.searchMoves)
        val tasks = helpers.map { helper ->
            val copy = position.copy()
            pool!!.submit { helper.search(copy, helperLimits, helpersStop) }
        }
        try {
            return main.search(position, limits, stop) { info -> listener(info.copy(nodes = workers.sumOf { it.searchedNodes })) }
        } finally {
            helpersStop.set(true)
            for (task in tasks) {
                try {
                    task.get()
                } catch (e: ExecutionException) {
                    System.err.println("Search helper error: ${e.cause}")
                }
            }
        }
    }

    override fun clear() {
        transpositionTable.clear()
        workers.forEach { it.clearHistory() }
    }

    companion object {
        const val MAX_THREADS = 256
    }
}
