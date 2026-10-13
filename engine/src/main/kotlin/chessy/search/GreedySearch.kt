package chessy.search

import chessy.core.BISHOP
import chessy.core.Color
import chessy.core.KNIGHT
import chessy.core.MoveGenerator
import chessy.core.MoveList
import chessy.core.PAWN
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.ROOK
import chessy.core.opposite
import chessy.core.popCount
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Simple reference player: searches all the moves up to a fixed [depth] and only counts material
 * (no positional evaluation, no quiescence search), to compare it with the real search and evaluators.
 *
 * - Depth 1: plays the move that leaves the best material, but does not see the replies, so it hangs pieces.
 * - Depth 2: also sees the best reply of the opponent, so it does not hang pieces to a direct capture.
 *
 * Checkmates and draws (stalemate, repetition, fifty-move rule, insufficient material) are detected.
 * Ties are broken at random, seeded with the position, so the same position always gets the same move.
 */
class GreedySearch(var depth: Int = 2) : Search {

    private val moveLists = Array(MAX_DEPTH + 1) { MoveList() }
    private var nodes = 0L

    override fun search(position: Position, limits: SearchLimits, stop: AtomicBoolean, listener: (SearchInfo) -> Unit): SearchResult {
        require(depth in 1..MAX_DEPTH) { "Greedy depth must be between 1 and $MAX_DEPTH" }
        val start = System.currentTimeMillis()
        val moves = MoveGenerator.legalMoves(position).toList().filter { limits.searchMoves.isEmpty() || it in limits.searchMoves }
        require(moves.isNotEmpty()) { "No legal moves to search" }

        val random = Random(position.key)
        nodes = 0
        var best = moves.first()
        var bestScore = -Score.INFINITE
        var bestTieBreak = 0

        for (move in moves) {
            position.makeMove(move)
            // Full window for each root move, so every score is exact and ties can be broken at random
            val score = -minimax(position, depth - 1, 1, -Score.INFINITE, Score.INFINITE)
            position.unmakeMove(move)

            val tieBreak = random.nextInt()
            if (score > bestScore || (score == bestScore && tieBreak > bestTieBreak)) {
                best = move
                bestScore = score
                bestTieBreak = tieBreak
            }
        }

        listener(SearchInfo(depth, depth, bestScore, nodes, System.currentTimeMillis() - start, listOf(best)))
        return SearchResult(best, bestScore)
    }

    /** Negamax with alpha-beta pruning, scoring only material. */
    private fun minimax(position: Position, depth: Int, ply: Int, alphaStart: Int, beta: Int): Int {
        nodes++
        val moves = moveLists[ply]
        MoveGenerator.generateLegal(position, moves)
        if (moves.isEmpty()) {
            return if (position.inCheck()) Score.matedIn(ply) else Score.DRAW
        }
        if (position.isInsufficientMaterial() || position.repetitions() >= 2 || position.halfmoveClock >= 100) {
            return Score.DRAW
        }
        if (depth == 0) {
            val us = position.sideToMove
            return material(position, us) - material(position, opposite(us))
        }

        var alpha = alphaStart
        var best = -Score.INFINITE
        for (i in 0 until moves.size) {
            val move = moves[i]
            position.makeMove(move)
            val score = -minimax(position, depth - 1, ply + 1, -beta, -alpha)
            position.unmakeMove(move)
            if (score > best) {
                best = score
                alpha = maxOf(alpha, score)
                if (alpha >= beta) break
            }
        }
        return best
    }

    private fun material(position: Position, color: Color): Int =
        popCount(position.pieces(color, PAWN)) * 100 +
        popCount(position.pieces(color, KNIGHT)) * 320 +
        popCount(position.pieces(color, BISHOP)) * 330 +
        popCount(position.pieces(color, ROOK)) * 500 +
        popCount(position.pieces(color, QUEEN)) * 900

    companion object {
        const val MAX_DEPTH = 4
    }
}
