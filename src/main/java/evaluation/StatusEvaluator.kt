package evaluation

import core.Board
import core.Status

open class StatusEvaluator(depth: Depth) : Evaluator(depth) {

    override fun getScore(board: Board): Score {
        with (board) {
            val own = getStatus()

            if (own == Status.CHECKMATE) {
                return -100000
            }

            val opponent = getStatus(turn.opponent())

            if (opponent == Status.CHECKMATE) {
                return 100000
            }

            if (own == Status.CHECK) {
                return if (opponent == Status.CHECK) 0 else -100
            }

            if (own.isDraw() || opponent.isDraw()) {
                return -10000
            }

            return 0
        }
    }

}