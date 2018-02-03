package pieces

import com.google.common.collect.ImmutableList
import core.Board
import core.Move
import core.Player

sealed class Piece(val type: PieceType, val player: Player) {

    abstract fun availableMovesOn(board: Board): ImmutableList<Move>

}