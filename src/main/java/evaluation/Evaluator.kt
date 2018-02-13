package evaluation

import core.Board
import me.carleslc.kotlin.extensions.number.even

typealias Score = Int
typealias Depth = Int

abstract class Evaluator(depth: Depth) {

    var depth = depth
        protected set(value) {
            field = value
            scoreMax = calculateScoreMax()
        }

    abstract fun getScore(board: Board): Score

    private fun calculateScoreMax(): (Board) -> Score = if (depth.even()) { { getScore(it) } } else { { -getScore(it) } }

    private var scoreMax = calculateScoreMax()

    fun getMaxScore(board: Board): Score = scoreMax(board)

}