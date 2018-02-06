package evaluation

import core.Board

interface Evaluator {

    fun getScore(board: Board): Int

}