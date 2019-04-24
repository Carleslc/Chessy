package search

import core.*
import evaluation.Depth
import evaluation.Score
import evaluation.ScoredMove
import exceptions.InvalidMovementException
import me.carleslc.kotlin.extensions.bytes.bytes
import me.carleslc.kotlin.extensions.strings.times
import me.carleslc.kotlin.extensions.time.Durations
import me.carleslc.kotlin.extensions.time.humanize
import me.carleslc.kotlin.extensions.time.millis
import me.carleslc.kotlin.extensions.time.minute
import java.time.Duration
import java.util.*

private val scoreCache = WeakHashMap<Triple<Depth, Chessboard, Player>, Pair<Score, ScoredMove?>>()

private val DEBUG_DEPTH = 3
private val PARALLEL = false

private val MAX_MILLIS = 1.minute.toMillis()

private var durationSum = 0L
private var steps = 0
private var maxMillisPerStep = 0L
private var searchCount = 0

private fun addRecord(duration: Duration) {
    durationSum += duration.toMillis()
    val millisStep = durationSum / ++steps
    if (EVALUATOR.depth > EVALUATOR.minDepth && (millisStep > maxMillisPerStep || getMemoryUsage().first > 70)) {
        EVALUATOR.increaseDepth(-1)
    } else if (EVALUATOR.depth < EVALUATOR.maxDepth && millisStep < maxMillisPerStep / 2) {
        EVALUATOR.increaseDepth(1)
    }
}

private fun getMemoryUsage(): Pair<Int, String> {
    val total = Runtime.getRuntime().totalMemory().bytes.toMegaBytes
    val free = Runtime.getRuntime().freeMemory().bytes.toMegaBytes
    val max = Runtime.getRuntime().maxMemory().bytes.toMegaBytes
    val usage = total - free
    val percent = ((usage.toDouble()/max) * 100).toInt()
    return percent to "$usage / $max MB in use ($percent%)"
}

private fun cache(board: Board, depth: Depth, move: ScoredMove) = move.also {
    val pair = it.score to it
    scoreCache[Triple(depth, board.board, board.turn)] = pair
    scoreCache[Triple(depth, board.board, board.turn.opponent())] = pair
}

private fun cache(board: Board, depth: Depth, score: Score) = score.also {
    val pair = it to null
    scoreCache[Triple(depth, board.board, board.turn)] = pair
    scoreCache[Triple(depth, board.board, board.turn.opponent())] = pair
}

private fun clearCache() {
    scoreCache.clear()
    EVALUATOR.clear()
    searchCount = 0
}

fun debug(s: String, depth: Depth, debugDepth: Depth) {
    val realDepth = EVALUATOR.depth - depth

    if (realDepth <= debugDepth) {
        println("\t" * realDepth + s)
    }
}

fun search(board: Board): ScoredMove {
    if (board.legalMoves.isEmpty()) {
        throw InvalidMovementException("Cannot move!")
    }

    val cached = scoreCache[Triple(EVALUATOR.depth, board.board, board.turn)]
    if (cached?.second != null) {
        return cached.second!!
    }

    val moves = board.legalMoves

    durationSum = 0L
    steps = 0
    maxMillisPerStep = MAX_MILLIS / moves.size

    val start = millis()
    val stream = if (PARALLEL) moves.parallelStream() else moves.stream()
    val bestMove = stream.map {
        if (PARALLEL) println(Thread.currentThread().name)
        debug(getMemoryUsage().second, EVALUATOR.depth, DEBUG_DEPTH)
        debug("Testing $it with DEPTH ${EVALUATOR.depth}", EVALUATOR.depth, DEBUG_DEPTH)
        val startStep = millis()
        val scored = ScoredMove(it, min(it.nextBoard, EVALUATOR.depth - 1, alpha = Int.MIN_VALUE, beta = Int.MAX_VALUE, debugDepth=DEBUG_DEPTH - 1))
        val time = Durations.fromMillis(startStep)
        addRecord(time)
        debug(time.humanize(), EVALUATOR.depth, DEBUG_DEPTH)
        debug("Move $scored (${((steps/board.legalMoves.size.toDouble()) * 100).toInt()}%)", EVALUATOR.depth, DEBUG_DEPTH)
        scored
    }.max(ScoredMove::compareTo).get()

    debug(Durations.fromMillis(start).humanize(), EVALUATOR.depth, DEBUG_DEPTH)
    debug("BEST: $bestMove", EVALUATOR.depth, DEBUG_DEPTH)

    val memory = getMemoryUsage().first
    if (++searchCount > 2 || memory > 70) {
        clearCache()
    } else {
        cache(board, EVALUATOR.depth, bestMove)
    }
    return bestMove
}

private fun min(board: Board, depth: Int, alpha: Int, beta: Int, debugDepth: Int = depth): Int {
    debug("${board.turn.getName()} (MIN)", depth, debugDepth)

    val cached = scoreCache[Triple(depth, board.board, board.turn)]
    if (cached != null) {
        debug("BOARD IN CACHE (${board.turn.getName()}): ${cached.first}", depth, debugDepth)
        return cached.first
    }

    if (depth == 0 || board.isEndGame()) {
        val score = cache(board, depth, EVALUATOR.getMaxScore(board))
        debug("Score $score", depth, debugDepth)
        return score
    }

    var currentBeta = beta

    for (move in board.legalMoves) {
        debug("Move $move", depth, debugDepth)
        val score = max(move.nextBoard, depth - 1, alpha, currentBeta, debugDepth)
        debug("Score $score", depth, debugDepth)
        if (score < currentBeta) {
            currentBeta = score
        }
        if (alpha >= currentBeta) {
            debug("SCORE: " + alpha, depth, debugDepth)
            return cache(board, depth, alpha)
        }
    }

    debug("SCORE: " + currentBeta, depth, debugDepth)
    return cache(board, depth, currentBeta)
}

private fun max(board: Board, depth: Int, alpha: Int, beta: Int, debugDepth: Int = depth): Int {
    debug("${board.turn.getName()} (MAX)", depth, debugDepth)

    val cached = scoreCache[Triple(depth, board.board, board.turn)]
    if (cached != null) {
        debug("BOARD IN CACHE (${board.turn.getName()}): ${cached.first}", depth, debugDepth)
        return cached.first
    }

    if (depth == 0 || board.isEndGame()) {
        val score = cache(board, depth, EVALUATOR.getMaxScore(board))
        debug("Score $score", depth, debugDepth)
        return score
    }

    var currentAlfa = alpha

    for (move in board.legalMoves) {
        debug("Move $move", depth, debugDepth)
        val score = min(move.nextBoard, depth - 1, currentAlfa, beta, debugDepth)
        debug("Score $score", depth, debugDepth)
        if (score > currentAlfa) {
            currentAlfa = score
        }
        if (currentAlfa >= beta) {
            debug("SCORE: " + beta, depth, debugDepth)
            return cache(board, depth, beta)
        }
    }

    debug("SCORE: " + currentAlfa, depth, debugDepth)
    return cache(board, depth, currentAlfa)
}