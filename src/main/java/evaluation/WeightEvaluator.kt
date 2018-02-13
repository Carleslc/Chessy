package evaluation

import core.Board
import core.Position
import pieces.Piece

class WeightEvaluator(depth: Depth) : StatusEvaluator(depth) {

    private val weight: (Map.Entry<Position, Piece>) -> Score = { it.value.type.weight }

    override fun getScore(board: Board): Score = with (board) {
        return super.getScore(board) + getPieces().sumBy(weight) - getPieces(turn.opponent()).sumBy(weight)
    }

}