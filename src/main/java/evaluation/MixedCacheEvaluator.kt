package evaluation

import core.Board
import core.Chessboard
import core.Player
import java.util.*

class MixedCacheEvaluator(vararg evaluators: Evaluator) : Evaluator(0) {

    private val sortedEvaluators = evaluators.sortedByDescending { it.depth }

    private val cache = WeakHashMap<Pair<Chessboard, Player>, Pair<Score, Depth>>()

    val minDepth = sortedEvaluators.last().depth
    val maxDepth = sortedEvaluators.first().depth

    init {
        depth = minDepth
    }

    fun increaseDepth(increment: Int) {
        val newDepth = depth + increment
        if (newDepth < minDepth || newDepth > maxDepth) {
            throw IllegalArgumentException("Depth not supported by evaluators")
        }
        depth += increment
    }

    fun clear() = cache.clear()

    operator fun contains(board: Board): Boolean = board.board to board.turn in cache

    operator fun get(board: Board): Score = getScore(board)

    override fun getScore(board: Board): Score {
        val pair = board.board to board.turn
        val current = cache[pair]
        if (current != null && depth <= current.second) {
            return current.first
        }
        val score = sortedEvaluators[maxDepth - depth].getScore(board)
        cache[pair] = score to depth
        cache[board.board to board.turn.opponent()] = -score to depth
        return score
    }

}