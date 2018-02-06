package evaluation

import core.Board

class WeightEvaluator : Evaluator {

    override fun getScore(board: Board): Int = board.getPieces().sumBy { it.value.type.weight }

}