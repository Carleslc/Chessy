package evaluation

import core.Board
import core.Player
import pieces.PieceType

/** Non-pawn material (both players) below which the king should be active (endgame king table). */
private const val ENDGAME_MATERIAL = 2600

/** Material and position weight of each piece. */
class WeightEvaluator : Evaluator {

    override fun getScore(board: Board): Score {
        val endGame = isEndGame(board)
        return weight(board, board.turn, endGame) - weight(board, board.turn.opponent(), endGame)
    }

    private fun weight(board: Board, player: Player, endGame: Boolean): Score = board.getPieces(player).sumBy {
        val type = it.value.type
        val table = if (type == PieceType.KING && endGame) KingTableEndGame else type.positionWeightTable
        type.weight + type.positionWeight(it.key, player, table)
    }

    private fun isEndGame(board: Board): Boolean {
        fun nonPawnMaterial(player: Player) = board.getPieces(player).sumBy { if (it.value.type == PieceType.PAWN) 0 else it.value.type.weight }
        return nonPawnMaterial(Player.WHITE) + nonPawnMaterial(Player.BLACK) <= ENDGAME_MATERIAL
    }

}
