package search

import core.Board
import core.Move
import evaluation.Depth
import evaluation.Evaluator
import evaluation.Score
import evaluation.ScoredMove
import evaluation.WeightEvaluator
import exceptions.InvalidMovementException

/** Score of a checkmate at the root. Mates found deeper score less, so the shortest mate is preferred. */
const val MATE: Score = 100_000
private const val INFINITY: Score = 1_000_000
/** Scores beyond this value are mates (distance to mate is at most 1000 plies). */
private const val MATE_BOUND: Score = MATE - 1000

private const val MAX_TABLE_ENTRIES = 1_000_000

/** Whether a stored score is exact or only a bound, because the search was cut by alpha-beta. */
private enum class Bound { EXACT, LOWER, UPPER }

private class TableEntry(val depth: Depth, val score: Score, val bound: Bound, val bestMove: Move?)

private class SearchTimeout : RuntimeException(null, null, false, false)

/**
 * Negamax with alpha-beta pruning, iterative deepening, transposition table and quiescence search.
 * Scores are always from the point of view of the player in turn.
 */
class AlphaBeta(private val evaluator: Evaluator = WeightEvaluator(),
                var maxDepth: Depth = 64,
                var maxMillis: Long = 5_000,
                var verbose: Boolean = true) {

    private val table = HashMap<Long, TableEntry>()

    /** Keys of the positions of the game (before the current one) and of the current search path. */
    private val history = mutableListOf<Long>()

    private var nodes = 0L
    private var deadline = 0L
    private var canTimeout = false

    /**
     * Finds the best move for the player in turn.
     * [gameHistory] are the keys of the previous positions of the game, to avoid (or seek) draws by repetition.
     */
    fun search(board: Board, gameHistory: List<Long> = emptyList()): ScoredMove {
        val moves = board.legalMoves
        if (moves.isEmpty()) {
            throw InvalidMovementException("Cannot move!")
        }
        if (table.size > MAX_TABLE_ENTRIES) {
            table.clear()
        }

        history.clear()
        history.addAll(gameHistory)
        nodes = 0
        val start = System.currentTimeMillis()
        deadline = start + maxMillis
        canTimeout = false

        var best = ScoredMove(moves.first(), 0)

        for (depth in 1..maxDepth) {
            try {
                best = searchRoot(board, depth, best.move)
            } catch (e: SearchTimeout) {
                if (verbose) println("Depth $depth timed out")
                break
            }
            // The first iteration always completes, so there is always a move
            canTimeout = true
            if (verbose) {
                val millis = System.currentTimeMillis() - start
                println("Depth $depth: $best ($nodes nodes, $millis ms)")
            }
            if (Math.abs(best.score) > MATE_BOUND) {
                break // Forced mate found
            }
        }

        return best
    }

    private fun searchRoot(board: Board, depth: Depth, previousBest: Move): ScoredMove {
        var alpha = -INFINITY
        var best: ScoredMove? = null

        history.add(board.key)
        for (move in orderMoves(board.legalMoves, previousBest)) {
            val score = -negamax(board.play(move), depth - 1, -INFINITY, -alpha, 1)
            if (best == null || score > best.score) {
                best = ScoredMove(move, score)
            }
            if (score > alpha) {
                alpha = score
            }
        }
        history.removeAt(history.lastIndex)

        return best!!
    }

    private fun negamax(board: Board, depth: Depth, alphaStart: Score, beta: Score, ply: Int): Score {
        checkTime()

        if (board.key in history) {
            return 0 // Repeating a position is a draw
        }

        val moves = board.legalMoves
        if (moves.isEmpty()) {
            return if (board.inCheck) -MATE + ply else 0
        }
        if (board.status.isDraw()) {
            return 0
        }

        val entry = table[board.key]
        if (entry != null && entry.depth >= depth) {
            val score = fromTable(entry.score, ply)
            when (entry.bound) {
                Bound.EXACT -> return score
                Bound.LOWER -> if (score >= beta) return score
                Bound.UPPER -> if (score <= alphaStart) return score
            }
        }

        if (depth <= 0) {
            return quiescence(board, alphaStart, beta, ply)
        }

        var alpha = alphaStart
        var bestScore = -INFINITY
        var bestMove: Move? = null

        history.add(board.key)
        for (move in orderMoves(moves, entry?.bestMove)) {
            val score = -negamax(board.play(move), depth - 1, -beta, -alpha, ply + 1)
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) {
                alpha = score
            }
            if (alpha >= beta) {
                break
            }
        }
        history.removeAt(history.lastIndex)

        val bound = when {
            bestScore <= alphaStart -> Bound.UPPER
            bestScore >= beta -> Bound.LOWER
            else -> Bound.EXACT
        }
        table[board.key] = TableEntry(depth, toTable(bestScore, ply), bound, bestMove)

        return bestScore
    }

    /** Searches only captures and promotions until the position is quiet, to avoid the horizon effect. */
    private fun quiescence(board: Board, alphaStart: Score, beta: Score, ply: Int): Score {
        checkTime()

        val moves = board.legalMoves
        if (moves.isEmpty()) {
            return if (board.inCheck) -MATE + ply else 0
        }

        val standPat = evaluator.getScore(board)
        if (standPat >= beta) {
            return standPat
        }
        var alpha = maxOf(alphaStart, standPat)

        for (move in moves) {
            if (!move.isCapture() && !move.isPromotion()) {
                continue
            }
            val score = -quiescence(board.play(move), -beta, -alpha, ply + 1)
            if (score >= beta) {
                return score
            }
            if (score > alpha) {
                alpha = score
            }
        }

        return alpha
    }

    /** Moves are already sorted by MVV-LVA; the best move from a previous search goes first. */
    private fun orderMoves(moves: List<Move>, bestMove: Move?): List<Move> {
        if (bestMove == null || bestMove !in moves) {
            return moves
        }
        return listOf(bestMove) + (moves - bestMove)
    }

    private fun checkTime() {
        if (++nodes and 1023L == 0L && canTimeout && System.currentTimeMillis() > deadline) {
            throw SearchTimeout()
        }
    }

    /** Mate scores are stored relative to the current node, so they are valid when reached from another ply. */
    private fun toTable(score: Score, ply: Int) = when {
        score > MATE_BOUND -> score + ply
        score < -MATE_BOUND -> score - ply
        else -> score
    }

    private fun fromTable(score: Score, ply: Int) = when {
        score > MATE_BOUND -> score - ply
        score < -MATE_BOUND -> score + ply
        else -> score
    }

}
