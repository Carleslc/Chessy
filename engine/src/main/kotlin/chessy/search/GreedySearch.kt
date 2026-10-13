package chessy.search

import chessy.core.BISHOP
import chessy.core.Color
import chessy.core.KNIGHT
import chessy.core.Move
import chessy.core.MoveGenerator
import chessy.core.PAWN
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.ROOK
import chessy.core.opposite
import chessy.core.popCount
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Provisional player until the real search (phase 3): looks only one ply ahead.
 *
 * It plays checkmate in one if possible, otherwise the move that leaves the best material balance,
 * scoring draws (stalemate, repetition...) as 0, so it avoids them when ahead and seeks them when behind.
 * It does not see the replies of the opponent, so it hangs pieces. Ties are broken at random,
 * seeded with the position, so the same position always gets the same move.
 */
class GreedySearch : Search {

    override fun search(position: Position, limits: SearchLimits, stop: AtomicBoolean, listener: (SearchInfo) -> Unit): SearchResult {
        val start = System.currentTimeMillis()
        val moves = MoveGenerator.legalMoves(position).toList().filter { limits.searchMoves.isEmpty() || it in limits.searchMoves }
        require(moves.isNotEmpty()) { "No legal moves to search" }

        val random = Random(position.key)
        val us = position.sideToMove
        var best = moves.first()
        var bestScore = -Score.INFINITE
        var bestTieBreak = 0

        for (move in moves) {
            position.makeMove(move)
            val score = when {
                MoveGenerator.legalMoves(position).isEmpty() -> if (position.inCheck()) Score.mateIn(1) else Score.DRAW
                position.isInsufficientMaterial() || position.repetitions() >= 2 || position.halfmoveClock >= 100 -> Score.DRAW
                else -> material(position, us) - material(position, opposite(us))
            }
            position.unmakeMove(move)

            val tieBreak = random.nextInt()
            if (score > bestScore || (score == bestScore && tieBreak > bestTieBreak)) {
                best = move
                bestScore = score
                bestTieBreak = tieBreak
            }
        }

        listener(SearchInfo(1, 1, bestScore, moves.size.toLong(), System.currentTimeMillis() - start, listOf(best)))
        return SearchResult(best, bestScore)
    }

    private fun material(position: Position, color: Color): Int =
        popCount(position.pieces(color, PAWN)) * 100 +
        popCount(position.pieces(color, KNIGHT)) * 320 +
        popCount(position.pieces(color, BISHOP)) * 330 +
        popCount(position.pieces(color, ROOK)) * 500 +
        popCount(position.pieces(color, QUEEN)) * 900
}
