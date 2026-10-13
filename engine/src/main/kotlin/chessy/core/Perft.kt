package chessy.core

/**
 * Counts the leaf nodes of the tree of legal moves up to a given depth, to verify the move generator
 * against known results (https://www.chessprogramming.org/Perft_Results).
 */
class Perft(private val position: Position) {

    /** One move list per ply, to avoid allocations. */
    private val moveLists = Array(MAX_DEPTH) { MoveList() }

    fun perft(depth: Int): Long = perft(depth, 0)

    /** Number of leaf nodes after each legal move of the root. */
    fun divide(depth: Int): List<Pair<Move, Long>> {
        require(depth >= 1) { "Depth must be at least 1" }
        val moves = MoveGenerator.legalMoves(position)
        return moves.toList().map { move ->
            position.makeMove(move)
            val nodes = perft(depth - 1, 1)
            position.unmakeMove(move)
            move to nodes
        }
    }

    private fun perft(depth: Int, ply: Int): Long {
        if (depth == 0) {
            return 1
        }
        val moves = moveLists[ply]
        MoveGenerator.generatePseudoLegal(position, moves)
        var nodes = 0L
        for (i in 0 until moves.size) {
            val move = moves[i]
            position.makeMove(move)
            if (!position.leftKingInCheck()) {
                nodes += perft(depth - 1, ply + 1)
            }
            position.unmakeMove(move)
        }
        return nodes
    }

    companion object {
        const val MAX_DEPTH = 64
    }
}
