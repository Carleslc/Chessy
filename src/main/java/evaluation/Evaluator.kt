package evaluation

import core.Board

typealias Score = Int
typealias Depth = Int

interface Evaluator {

    /** Static score of the board from the point of view of the player in turn (positive is better for that player). */
    fun getScore(board: Board): Score

}
