package search

import core.Board
import core.EVALUATOR
import core.PARALLEL
import evaluation.ScoredMove
import exceptions.InvalidMovementException
import me.carleslc.kotlin.extensions.bytes.bytes
import me.carleslc.kotlin.extensions.strings.times
import me.carleslc.kotlin.extensions.time.Durations
import me.carleslc.kotlin.extensions.time.humanize
import me.carleslc.kotlin.extensions.time.millis

fun getMemoryUsage(): Pair<Int, String> {
    val total = Runtime.getRuntime().totalMemory().bytes.toMegaBytes
    val free = Runtime.getRuntime().freeMemory().bytes.toMegaBytes
    val max = Runtime.getRuntime().maxMemory().bytes.toMegaBytes
    val usage = total - free
    val percent = ((usage.toDouble()/max) * 100).toInt()
    return percent to "$usage / $max MB in use ($percent%)"
}

fun searchDebug(board: Board, debugDepth: Int = EVALUATOR.depth): ScoredMove {
    if (board.legalMoves.isEmpty()) {
        throw InvalidMovementException("Cannot move!")
    }

    val moves = board.legalMoves

    durationSum = 0L
    steps = 0
    maxMillisPerStep = MAX_MILLIS / moves.size

    val start = millis()
    val stream = if (PARALLEL) moves.parallelStream() else moves.stream()
    val bestMove = stream.map {
        if (PARALLEL) println(Thread.currentThread().name)
        println(getMemoryUsage().second)
        println("Testing $it with DEPTH ${EVALUATOR.depth}")
        val startStep = millis()
        val scored = ScoredMove(it, minDebug(it.nextBoard, EVALUATOR.depth - 1, alpha = Int.MIN_VALUE, beta = Int.MAX_VALUE, debugDepth = debugDepth - 1))
        val time = Durations.fromMillis(startStep)
        addRecord(time)
        println(time.humanize())
        println("Move $scored (${((steps/board.legalMoves.size.toDouble()) * 100).toInt()}%)")
        scored
    }.max(ScoredMove::compareTo).get()

    println(Durations.fromMillis(start).humanize())
    println("BEST: " + bestMove)

    cache(board, EVALUATOR.depth, bestMove)
    if (++searchCount > 2) {
        clearCache()
    }
    return bestMove
}

private fun minDebug(board: Board, depth: Int, alpha: Int, beta: Int, debugDepth: Int = depth): Int {
    fun debug(s: String) {
        val realDepth = EVALUATOR.depth - depth

        if (realDepth <= debugDepth) {
            println("\t" * realDepth + s)
        }
    }
    debug("${board.turn.getName()} (MIN)")

    val cached = scoreCache[Triple(depth, board.board, board.turn)]
    if (cached != null) {
        println("BOARD IN CACHE (${board.turn.getName()}): ${scoreCache[Triple(depth, board.board, board.turn)]}")
        return cached.first
    }

    if (depth == 0 || board.isEndGame()) {
        val score = cache(board, depth, EVALUATOR.getMaxScore(board))
        debug("Score $score")
        return score
    }

    var currentBeta = beta

    for (move in board.legalMoves) {
        debug("Move $move")
        val score = maxDebug(move.nextBoard, depth - 1, alpha, currentBeta, debugDepth)
        debug("Score $score")
        if (score < currentBeta) {
            currentBeta = score
        }
        if (alpha >= currentBeta) {
            debug("SCORE: " + alpha)
            return cache(board, depth, alpha)
        }
    }

    debug("SCORE: " + currentBeta)
    return cache(board, depth, currentBeta)
}

private fun maxDebug(board: Board, depth: Int, alfa: Int, beta: Int, debugDepth: Int = depth): Int {
    fun debug(s: String) {
        val realDepth = EVALUATOR.depth - depth

        if (realDepth <= debugDepth) {
            println("\t" * realDepth + s)
        }
    }

    debug("${board.turn.getName()} (MAX)")

    val cached = scoreCache[Triple(depth, board.board, board.turn)]
    if (cached != null) {
        println("BOARD IN CACHE (${board.turn.getName()}): ${scoreCache[Triple(depth, board.board, board.turn)]}")
        return cached.first
    }

    if (depth == 0 || board.isEndGame()) {
        val score = cache(board, depth, EVALUATOR.getMaxScore(board))
        debug("Score $score")
        return score
    }

    var currentAlfa = alfa

    for (move in board.legalMoves) {
        debug("Move $move")
        val score = minDebug(move.nextBoard,depth - 1, currentAlfa, beta, debugDepth)
        debug("Score $score")
        if (score > currentAlfa) {
            currentAlfa = score
        }
        if (currentAlfa >= beta) {
            debug("SCORE: " + beta)
            return cache(board, depth, beta)
        }
    }

    debug("SCORE: " + currentAlfa)
    return cache(board, depth, currentAlfa)
}