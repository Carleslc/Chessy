package snaky.search

import snaky.core.Color
import snaky.core.WHITE

/**
 * Decides how long to think about a move (https://www.chessprogramming.org/Time_Management).
 *
 * - [softLimit]: target time. A new iteration is not started when it would probably not finish before it.
 * - [hardLimit]: the search is aborted when it is reached.
 *
 * Both are null when there is no time limit (infinite, or only depth or nodes limits).
 *
 * When pondering, the limits count from the `ponderhit` (the clock of the engine starts when the opponent plays
 * the expected move), and until then there is no limit. The time already spent pondering is a gift.
 */
class TimeManager(limits: SearchLimits, sideToMove: Color, private val searchStart: Long = System.currentTimeMillis()) {

    private val ponderhit: Ponderhit? = if (limits.ponder) limits.ponderhit else null

    val softLimit: Long?
    val hardLimit: Long?

    init {
        val clock = if (sideToMove == WHITE) limits.whiteTime else limits.blackTime
        val increment = if (sideToMove == WHITE) limits.whiteIncrement else limits.blackIncrement

        when {
            limits.infinite -> {
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

    /**
     * Whether to start another iteration: each iteration takes longer than all the previous ones together.
     * [elapsed] is the time since the start of the search.
     */
    fun canStartIteration(elapsed: Long): Boolean {
        val used = timeUsed(elapsed) ?: return true
        return softLimit == null || used < softLimit * NEW_ITERATION_FRACTION
    }

    fun mustAbort(elapsed: Long): Boolean {
        val used = timeUsed(elapsed) ?: return false
        return hardLimit != null && used >= hardLimit
    }

    /** Time used from the clock of the engine, or null while pondering (the clock has not started). */
    private fun timeUsed(elapsed: Long): Long? = when {
        ponderhit == null -> elapsed
        ponderhit.time == Ponderhit.NOT_YET -> null
        else -> elapsed - (ponderhit.time - searchStart)
    }

    companion object {
        /** Expected number of remaining moves when the time control does not tell it. */
        const val DEFAULT_MOVES_TO_GO = 30

        /** Do not start a new iteration after this fraction of the soft limit. */
        const val NEW_ITERATION_FRACTION = 0.6
    }
}
