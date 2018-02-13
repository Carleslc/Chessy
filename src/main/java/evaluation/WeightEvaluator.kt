package evaluation

import core.Board
import core.Player
import core.Position
import pieces.Piece
import pieces.PieceType

class WeightEvaluator(depth: Depth) : StatusEvaluator(depth) {

    private val positionWeightWhite: (PieceType, Position) -> Score = { type, pos -> type.positionWeightWhite(pos) }
    private val positionWeightBlack: (PieceType, Position) -> Score = { type, pos -> type.positionWeightBlack(pos) }
    private var positionWeight = positionWeightWhite

    private val weight: (Map.Entry<Position, Piece>) -> Score = { it.value.type.weight + positionWeight(it.value.type, it.key) }

    override fun getScore(board: Board): Score = with (board) {
        var score = super.getScore(board)
        setPositionWeight(turn)
        score += getPieces().sumBy(weight)
        val opponent = turn.opponent()
        setPositionWeight(opponent)
        score -= getPieces(opponent).sumBy(weight)
        return score
    }

    private fun setPositionWeight(player: Player) {
        positionWeight = if (player == Player.WHITE) positionWeightWhite else positionWeightBlack
    }

}