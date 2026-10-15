package snaky.core

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

        /**
         * [divide] using several threads. Each thread works on its own copy of the position.
         * The tree is split two plies below the root, so there are many small tasks to keep all threads busy.
         */
        fun parallelDivide(position: Position, depth: Int, threads: Int): List<Pair<Move, Long>> {
            require(depth >= 1) { "Depth must be at least 1" }
            val root = position.copy()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
            try {
                val tasks = MoveGenerator.legalMoves(root).toList().map { move ->
                    root.makeMove(move)
                    val replies = if (depth >= 2) MoveGenerator.legalMoves(root).toList() else emptyList()
                    val fen = root.toFen()
                    root.unmakeMove(move)
                    val subtasks = if (depth == 1) {
                        listOf(pool.submit<Long> { 1L })
                    } else {
                        replies.map { reply ->
                            pool.submit<Long> {
                                val copy = Position.fromFen(fen)
                                copy.makeMove(reply)
                                Perft(copy).perft(depth - 2)
                            }
                        }
                    }
                    move to subtasks
                }
                return tasks.map { (move, subtasks) -> move to subtasks.sumOf { it.get() } }
            } finally {
                pool.shutdown()
            }
        }

        fun parallelPerft(position: Position, depth: Int, threads: Int): Long =
            if (depth == 0) 1 else parallelDivide(position, depth, threads).sumOf { it.second }
    }
}
