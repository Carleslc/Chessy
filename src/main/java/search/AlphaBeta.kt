package search

import core.*
import evaluation.Depth
import evaluation.Score
import evaluation.ScoredMove
import exceptions.InvalidMovementException
import me.carleslc.kotlin.extensions.time.Durations
import me.carleslc.kotlin.extensions.time.millis
import me.carleslc.kotlin.extensions.time.minute
import java.time.Duration
import java.util.*

internal val scoreCache = WeakHashMap<Triple<Depth, Chessboard, Player>, Pair<Score, ScoredMove?>>()

internal val MAX_MILLIS = 1.minute.toMillis()

internal var durationSum = 0L
internal var steps = 0
internal var maxMillisPerStep = 0L
internal var searchCount = 0

internal fun addRecord(duration: Duration) {
    durationSum += duration.toMillis()
    val millisStep = durationSum / ++steps
    if (EVALUATOR.depth > EVALUATOR.minDepth && (millisStep > maxMillisPerStep || getMemoryUsage().first > 80)) {
        EVALUATOR.increaseDepth(-1)
    } else if (EVALUATOR.depth < EVALUATOR.maxDepth && millisStep < maxMillisPerStep / 2) {
        EVALUATOR.increaseDepth(1)
    }
}

fun clearCache() {
    scoreCache.clear()
    EVALUATOR.clear()
    searchCount = 0
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

    val stream = if (PARALLEL) moves.parallelStream() else moves.stream()
    val bestMove = stream.map {
        val startStep = millis()
        val scored = ScoredMove(it, min(it.nextBoard, EVALUATOR.depth - 1, alpha = Int.MIN_VALUE, beta = Int.MAX_VALUE))
        addRecord(Durations.fromMillis(startStep))
        scored
    }.max(ScoredMove::compareTo).get()

    cache(board, EVALUATOR.depth, bestMove)

    if (++searchCount > 2) {
        clearCache()
    }
    return bestMove
}

internal fun cache(board: Board, depth: Depth, move: ScoredMove) = move.also {
    val pair = it.score to it
    scoreCache[Triple(depth, board.board, board.turn)] = pair
    scoreCache[Triple(depth, board.board, board.turn.opponent())] = pair
}

internal fun cache(board: Board, depth: Depth, score: Score) = score.also {
    val pair = it to null
    scoreCache[Triple(depth, board.board, board.turn)] = pair
    scoreCache[Triple(depth, board.board, board.turn.opponent())] = pair
}

private fun min(board: Board, depth: Int, alpha: Int, beta: Int): Int {
    val cached = scoreCache[Triple(depth, board.board, board.turn)]
    if (cached != null) {
        return cached.first
    }

    if (depth == 0 || board.isEndGame()) {
        return cache(board, depth, EVALUATOR.getMaxScore(board))
    }

    var currentBeta = beta

    for (move in board.legalMoves) {
        val score = max(move.nextBoard, depth - 1, alpha, currentBeta)
        if (score < currentBeta) {
            currentBeta = score
        }
        if (alpha >= currentBeta) {
            return cache(board, depth, alpha)
        }
    }

    return cache(board, depth, currentBeta)
}

private fun max(board: Board, depth: Int, alpha: Int, beta: Int): Int {
    val cached = scoreCache[Triple(depth, board.board, board.turn)]
    if (cached != null) {
        return cached.first
    }

    if (depth == 0 || board.isEndGame()) {
        return cache(board, depth, EVALUATOR.getMaxScore(board))
    }

    var currentAlfa = alpha

    for (move in board.legalMoves) {
        val score = min(move.nextBoard, depth - 1, currentAlfa, beta)
        if (score > currentAlfa) {
            currentAlfa = score
        }
        if (currentAlfa >= beta) {
            return cache(board, depth, beta)
        }
    }

    return cache(board, depth, currentAlfa)
}