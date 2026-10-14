package chessy.search

import chessy.core.Move

/**
 * Transposition table (https://www.chessprogramming.org/Transposition_Table): a hash table of the positions already
 * searched, indexed by their Zobrist key, with the best move found, the score, the depth and the bound type.
 *
 * The same position is reached by different move orders (transpositions) and is searched again in each iteration,
 * so the stored results save searches and the stored move is searched first.
 *
 * Each entry is two longs: the key XOR the data, and the data (lockless hashing, by Robert Hyatt).
 * When several threads share the table and write the same entry at the same time, the key does not match the data
 * and the entry is ignored. Stored moves can still be wrong (hash collisions), so they are only used to order
 * the generated moves, never played without being generated.
 */
class TranspositionTable(sizeMb: Int = DEFAULT_SIZE_MB) {

    private var keys = LongArray(0)
    private var data = LongArray(0)
    private var mask = 0

    /** Age of the entries: entries of previous searches are replaced first. */
    private var generation = 0

    init {
        resize(sizeMb)
    }

    val entries: Int get() = data.size

    fun resize(sizeMb: Int) {
        require(sizeMb >= 1) { "The transposition table needs at least 1 MB" }
        val size = java.lang.Long.highestOneBit(sizeMb.toLong() * 1024 * 1024 / ENTRY_BYTES).toInt()
        keys = LongArray(size)
        data = LongArray(size)
        mask = size - 1
        generation = 0
    }

    fun clear() {
        keys.fill(0)
        data.fill(0)
        generation = 0
    }

    /** Called at the start of each search. */
    fun newSearch() {
        generation = (generation + 1) and 0xFF
    }

    /** Data of the entry of the position with this [key], or [NO_ENTRY]. Read it with the companion functions. */
    fun probe(key: Long): Long {
        val index = index(key)
        val entry = data[index]
        return if (entry != NO_ENTRY && keys[index] xor entry == key) entry else NO_ENTRY
    }

    /**
     * Stores the result of a search. The [score] must be relative to the position (see [scoreToTable]).
     * A different position replaces the entry if it is from a previous search or searched at least as deep.
     */
    fun store(key: Long, move: Move, score: Int, depth: Int, bound: Int) {
        val index = index(key)
        val old = data[index]
        val samePosition = old != NO_ENTRY && keys[index] xor old == key
        if (!samePosition && old != NO_ENTRY && generationOf(old) == generation && depthOf(old) > depth) {
            return // Keep the deeper search of the other position
        }
        // Keep the move of a previous search of the same position when this one did not find a best move
        val bestMove = if (move == Move.NONE && samePosition) moveOf(old) else move
        val entry = pack(bestMove, score, depth, bound, generation)
        data[index] = entry
        keys[index] = key xor entry
    }

    /** Permille of the table used in the current search (UCI `hashfull`), sampling the first 1000 entries. */
    fun hashfull(): Int {
        val sample = minOf(1000, data.size)
        var used = 0
        for (i in 0 until sample) {
            if (data[i] != NO_ENTRY && generationOf(data[i]) == generation) used++
        }
        return used * 1000 / sample
    }

    private fun index(key: Long): Int = (key xor (key ushr 32)).toInt() and mask

    companion object {
        const val DEFAULT_SIZE_MB = 64
        const val MAX_SIZE_MB = 1024
        private const val ENTRY_BYTES = 16

        const val NO_ENTRY = 0L

        /** The score is a lower bound: the search failed high (score >= beta), there may be a better move. */
        const val LOWER_BOUND = 1
        /** The score is an upper bound: the search failed low (score <= alpha), every move is at most this good. */
        const val UPPER_BOUND = 2
        /** The score is exact. */
        const val EXACT = 3

        // Entry data: move (16 bits) | score + 32768 (16 bits) | depth (8 bits) | bound (2 bits) | generation (8 bits)

        private fun pack(move: Move, score: Int, depth: Int, bound: Int, generation: Int): Long =
            (move.bits.toLong() and 0xFFFF) or
            ((score + 32768).toLong() shl 16) or
            (depth.coerceIn(0, 255).toLong() shl 32) or
            (bound.toLong() shl 40) or
            (generation.toLong() shl 42)

        fun moveOf(entry: Long) = Move((entry and 0xFFFF).toInt())

        fun scoreOf(entry: Long) = ((entry ushr 16) and 0xFFFF).toInt() - 32768

        fun depthOf(entry: Long) = ((entry ushr 32) and 0xFF).toInt()

        fun boundOf(entry: Long) = ((entry ushr 40) and 0x3).toInt()

        private fun generationOf(entry: Long) = ((entry ushr 42) and 0xFF).toInt()

        /**
         * Mate scores are relative to the root (mate in N plies from the root), but in the table they must be
         * relative to the position, because the same position can be reached at a different ply.
         */
        fun scoreToTable(score: Int, ply: Int): Int = when {
            score >= Score.MATE - Score.MAX_PLY -> score + ply
            score <= -Score.MATE + Score.MAX_PLY -> score - ply
            else -> score
        }

        fun scoreFromTable(score: Int, ply: Int): Int = when {
            score >= Score.MATE - Score.MAX_PLY -> score - ply
            score <= -Score.MATE + Score.MAX_PLY -> score + ply
            else -> score
        }
    }
}
