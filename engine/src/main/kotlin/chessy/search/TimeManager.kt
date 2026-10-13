package chessy.search

import chessy.core.Color
import chessy.core.WHITE

/**
 * Decides how long to think about a move (https://www.chessprogramming.org/Time_Management).
 *
 * - [softLimit]: target time. A new iteration is not started when it would probably not finish before it.
 * - [hardLimit]: the search is aborted when it is reached.
 *
 * Both are null when there is no time limit (infinite, ponder, or only depth or nodes limits).
 */
class TimeManager(limits: SearchLimits, sideToMove: Color) {

    val softLimit: Long?
    val hardLimit: Long?

    init {
        val clock = if (sideToMove == WHITE) limits.whiteTime else limits.blackTime
        val increment = if (sideToMove == WHITE) limits.whiteIncrement else limits.blackIncrement

        when {
            limits.infinite || limits.ponder -> {
                softLimit = null
                hardLimit = null
            }
            limits.moveTime != null -> {
                val time = maxOf(1, limits.moveTime - limits.moveOverhead)
                softLimit = time
                hardLimit = time
            }
            clock != null -> {
                // Keep a reserve for the communication delays, and spread the remaining time over the next moves
                val available = maxOf(1, clock - limits.moveOverhead)
                val movesToGo = limits.movesToGo?.coerceIn(1, DEFAULT_MOVES_TO_GO) ?: DEFAULT_MOVES_TO_GO
                softLimit = maxOf(1, minOf(available / movesToGo + increment * 3 / 4, available / 2))
                hardLimit = maxOf(softLimit, minOf(softLimit * 4, available * 3 / 4))
            }
            else -> {
                softLimit = null
                hardLimit = null
            }
        }
    }

    val hasLimit: Boolean get() = hardLimit != null

    /** Whether to start another iteration: each iteration takes longer than all the previous ones together. */
    fun canStartIteration(elapsed: Long): Boolean = softLimit == null || elapsed < softLimit * NEW_ITERATION_FRACTION

    fun mustAbort(elapsed: Long): Boolean = hardLimit != null && elapsed >= hardLimit

    companion object {
        /** Expected number of remaining moves when the time control does not tell it. */
        const val DEFAULT_MOVES_TO_GO = 30

        /** Do not start a new iteration after this fraction of the soft limit. */
        const val NEW_ITERATION_FRACTION = 0.6
    }
}
