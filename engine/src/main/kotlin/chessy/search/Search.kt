package chessy.search

import chessy.core.Move
import chessy.core.Position
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the best move of a position.
 *
 * Implementations are interchangeable: the UCI protocol and the console games only depend on this interface.
 */
interface Search {

    /**
     * Searches the [position] (which has at least one legal move) within the [limits].
     *
     * It must return as soon as possible when [stop] is set, and leave the [position] as it was.
     * Progress is reported to the [listener] (sent to the GUI as UCI `info` lines).
     */
    fun search(position: Position, limits: SearchLimits, stop: AtomicBoolean, listener: (SearchInfo) -> Unit = {}): SearchResult

    /** Forgets everything learned in previous searches (such as the transposition table), for a new game. */
    fun clear() {}
}

/** Limits of a search, as given by the UCI `go` command. Null means no limit. Times are in milliseconds. */
data class SearchLimits(
    val depth: Int? = null,
    val nodes: Long? = null,
    val moveTime: Long? = null,
    val whiteTime: Long? = null,
    val blackTime: Long? = null,
    val whiteIncrement: Long = 0,
    val blackIncrement: Long = 0,
    val movesToGo: Int? = null,
    /** Search a mate in this number of moves. */
    val mate: Int? = null,
    /** Search until `stop`. */
    val infinite: Boolean = false,
    /**
     * Search on the opponent's time, expecting the ponder move: the time limits only start to count at [ponderhit]
     * (the opponent played the expected move), and until then the search goes on until `stop`.
     */
    val ponder: Boolean = false,
    val ponderhit: Ponderhit = Ponderhit(),
    /** Only consider these root moves (all the legal moves if empty). */
    val searchMoves: List<Move> = emptyList(),
    /** Time to keep in reserve for each move, for the communication delays between the GUI and the engine. */
    val moveOverhead: Long = 0,
)

/** Signal of the UCI `ponderhit` command: the opponent played the expected move, so the clock of the engine starts. */
class Ponderhit {
    /** Time of the signal (System.currentTimeMillis), or [NOT_YET]. */
    @Volatile
    var time: Long = NOT_YET
        private set

    fun signal() {
        time = System.currentTimeMillis()
    }

    companion object {
        const val NOT_YET = -1L
    }
}

data class SearchResult(val bestMove: Move, val score: Int, val ponderMove: Move = Move.NONE)

/** Progress of the search, sent to the GUI as an UCI `info` line. */
data class SearchInfo(
    val depth: Int,
    val selectiveDepth: Int,
    val score: Int,
    val nodes: Long,
    val timeMillis: Long,
    val principalVariation: List<Move>,
    /** Permille of the transposition table in use, if any. */
    val hashfull: Int? = null,
) {
    /** UCI `info` line (with [chess960], castling in the principal variation as the king taking its rook). */
    fun toUci(chess960: Boolean = false): String {
        val nps = if (timeMillis > 0) nodes * 1000 / timeMillis else 0
        val pv = principalVariation.joinToString(" ") { it.toUci(chess960) }
        val hash = if (hashfull != null) " hashfull $hashfull" else ""
        return "info depth $depth seldepth $selectiveDepth score ${Score.toUci(score)} nodes $nodes nps $nps$hash time $timeMillis pv $pv"
    }
}

/**
 * Scores in centipawns from the point of view of the side to move.
 * Checkmates are scored as [MATE] minus the plies to reach them, so shorter mates score higher.
 */
object Score {
    const val MATE = 32_000
    const val INFINITE = 32_001
    const val DRAW = 0

    /** Maximum plies of a search, so scores beyond `MATE - MAX_PLY` are mates. */
    const val MAX_PLY = 256

    /** Score of giving mate in [ply] plies. */
    fun mateIn(ply: Int) = MATE - ply

    /** Score of being mated in [ply] plies. */
    fun matedIn(ply: Int) = -MATE + ply

    fun isMate(score: Int) = Math.abs(score) >= MATE - MAX_PLY

    /** UCI score: `cp 35`, or `mate 3` / `mate -2` in moves (not plies). */
    fun toUci(score: Int): String = when {
        score >= MATE - MAX_PLY -> "mate ${(MATE - score + 1) / 2}"
        score <= -MATE + MAX_PLY -> "mate ${-(MATE + score) / 2}"
        else -> "cp $score"
    }
}
