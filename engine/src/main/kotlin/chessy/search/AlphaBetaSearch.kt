package chessy.search

import chessy.core.BISHOP
import chessy.core.KING
import chessy.core.KNIGHT
import chessy.core.Move
import chessy.core.MoveGenerator
import chessy.core.MoveList
import chessy.core.NO_PIECE
import chessy.core.PAWN
import chessy.core.PIECES
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.ROOK
import chessy.core.popCount
import chessy.core.StaticExchange
import chessy.core.typeOf
import chessy.eval.Evaluator
import chessy.eval.MaterialEvaluator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Negamax with alpha-beta pruning (https://www.chessprogramming.org/Alpha-Beta), the main search of Chessy:
 *
 * - Iterative deepening: searches depth 1, 2, 3... while there is time, so the depth adapts to each position
 *   (deeper in endgames, with fewer moves) and the best move of the previous iteration is searched first.
 * - Principal variation search (PVS): the first move is searched with the full window, the others with a null window
 *   (only to prove that they are not better), and searched again only if they are.
 * - Aspiration windows: from depth 5, the root is searched with a narrow window around the previous score,
 *   widened when the score falls outside.
 * - Null move pruning: if passing the turn still leaves the position above beta, a real move will be even better,
 *   so the node is cut after a reduced search. It fails in zugzwang (every move makes the position worse, so passing
 *   would be the best move), which is what a constrictor seeks: it is not used without pieces, and with few pieces
 *   or in deep searches the cutoff is verified by searching the position again without null move.
 * - Late move reductions (LMR): quiet moves ordered late are probably bad, so they are searched with less depth,
 *   and searched again with the full depth only if they turn out to be good.
 * - Pruning near the leaves (not in the principal variation nor in check): reverse futility pruning (the static
 *   evaluation is so far above beta that the node is cut), futility pruning (quiet moves that cannot reach alpha)
 *   and late move pruning (the latest quiet moves are skipped).
 * - Quiescence search: at the end of the main search, captures and promotions are searched until the position is quiet,
 *   so the evaluation is not fooled by a pending capture (horizon effect). Captures losing material (SEE) are skipped.
 * - Transposition table: results of positions already searched (by other move orders or in previous iterations)
 *   are reused, and their best move is searched first.
 * - Move ordering: move of the transposition table and principal variation first, then captures by MVV-LVA
 *   (most valuable victim, least valuable attacker), then promotions, then killer moves (quiet moves that caused
 *   a cutoff at the same ply), the countermove (the quiet move that refuted the last move of the opponent)
 *   and the other quiet moves by their history (how often they caused cutoffs),
 *   and finally the captures that lose material according to the static exchange evaluation (SEE).
 *   Good moves first produce more cutoffs.
 * - Checkmates are scored by distance (shorter mates are preferred) and draws by repetition, fifty-move rule
 *   and insufficient material are detected inside the search. Draws are scored with the contempt of the evaluator:
 *   negative for the engine (it avoids draws unless it is worse), positive for the opponent.
 * - Progress: the static evaluation shrinks towards a draw as the fifty-move counter grows, so the side that is better
 *   prefers to make progress (pawn moves, captures) instead of maneuvering forever.
 *
 * Scores are always from the point of view of the side to move (negamax: the score of a move is the negated score
 * of the position after it for the opponent).
 */
class AlphaBetaSearch(
    var evaluator: Evaluator = MaterialEvaluator(),
    /** Null to search without transposition table (to test it). */
    val transpositionTable: TranspositionTable? = TranspositionTable(),
) : Search {

    /**
     * Helper of a parallel search (see [ParallelSearch]): it does not start a new search in the shared
     * transposition table, and searches [depthOffset] plies deeper than the main thread to diverge from it.
     */
    var isHelper = false
    var depthOffset = 0

    /** Nodes searched in the current search (read by the parallel search to report the total). */
    val searchedNodes: Long get() = nodes

    // Preallocated per ply, to avoid allocations during the search
    private val moveLists = Array(MAX_PLY + 1) { MoveList() }
    private val orderScores = Array(MAX_PLY + 1) { IntArray(MoveList.MAX_MOVES) }

    /** Triangular principal variation table: pv[ply] is the best line found from that ply. */
    private val pv = Array(MAX_PLY + 1) { IntArray(MAX_PLY + 1) }
    private val pvLength = IntArray(MAX_PLY + 1)

    /** Principal variation of the previous iteration, searched first. */
    private var previousPv = IntArray(0)

    /** Two quiet moves per ply that caused a beta cutoff (https://www.chessprogramming.org/Killer_Heuristic). */
    private val killers = Array(MAX_PLY + 1) { IntArray(2) }

    /** history[color][from * 64 + to]: how good quiet moves were (https://www.chessprogramming.org/History_Heuristic). */
    private val history = Array(2) { IntArray(64 * 64) }

    /**
     * counterMoves[piece * 64 + to]: quiet move that caused a cutoff after the opponent moved that piece to that square
     * (https://www.chessprogramming.org/Countermove_Heuristic).
     */
    private val counterMoves = IntArray(PIECES * 64)

    /** Quiet moves searched at each ply before a cutoff, to lower their history. */
    private val quietsSearched = Array(MAX_PLY + 1) { IntArray(MoveList.MAX_MOVES) }

    private lateinit var position: Position
    private lateinit var limits: SearchLimits
    private lateinit var stop: AtomicBoolean
    private lateinit var timeManager: TimeManager
    /** Color of the engine in this search, for the contempt. */
    private var rootColor = 0
    /** Depth of the current iteration, to limit the extensions. */
    private var rootDepth = 0
    private var startTime = 0L
    private var nodes = 0L
    private var selectiveDepth = 0
    private var aborted = false
    /** The first iteration always finishes, so there is always a move. */
    private var canAbort = false

    override fun search(position: Position, limits: SearchLimits, stop: AtomicBoolean, listener: (SearchInfo) -> Unit): SearchResult {
        this.position = position
        this.limits = limits
        this.stop = stop
        startTime = System.currentTimeMillis()
        timeManager = TimeManager(limits, position.sideToMove, startTime)
        rootColor = position.sideToMove
        nodes = 0
        selectiveDepth = 0
        aborted = false
        previousPv = IntArray(0)
        if (!isHelper) transpositionTable?.newSearch()
        killers.forEach { it.fill(Move.NONE.bits) }
        agingHistory()

        val rootMoves = MoveGenerator.legalMoves(position).toList().filter { limits.searchMoves.isEmpty() || it in limits.searchMoves }
        require(rootMoves.isNotEmpty()) { "No legal moves to search" }

        var result = SearchResult(rootMoves.first(), 0)
        val maxDepth = minOf(limits.depth ?: MAX_DEPTH, MAX_DEPTH)

        for (depth in 1..maxDepth) {
            canAbort = depth > 1
            selectiveDepth = 0
            rootDepth = depth + depthOffset
            val score = aspirationSearch(rootDepth, result.score)
            if (aborted) {
                break // The previous iteration is the last complete one
            }

            val line = (0 until pvLength[0]).map { Move(pv[0][it]) }
            previousPv = IntArray(line.size) { line[it].bits }
            result = SearchResult(line.first(), score, line.getOrNull(1) ?: Move.NONE)
            listener(SearchInfo(depth, maxOf(depth, selectiveDepth), score, nodes, elapsed(), line, transpositionTable?.hashfull()))

            val pliesToMate = Score.MATE - Math.abs(score)
            when {
                stop.get() -> break
                Score.isMate(score) && depth >= pliesToMate -> break // Every reply was searched: the mate is proven
                limits.mate != null && score >= Score.mateIn(limits.mate * 2 - 1) -> break
                rootMoves.size == 1 && timeManager.hasLimit -> break // Do not waste time with a forced move
                !timeManager.canStartIteration(elapsed()) -> break
            }
        }

        return result
    }

    /** Searches the root with a window around the [previousScore], widening it until the score is inside. */
    private fun aspirationSearch(depth: Int, previousScore: Int): Int {
        if (depth < ASPIRATION_MIN_DEPTH || Score.isMate(previousScore)) {
            return negamax(depth, 0, -Score.INFINITE, Score.INFINITE, onPv = true)
        }
        var window = ASPIRATION_WINDOW
        var alpha = maxOf(previousScore - window, -Score.INFINITE)
        var beta = minOf(previousScore + window, Score.INFINITE)
        while (true) {
            val score = negamax(depth, 0, alpha, beta, onPv = true)
            if (aborted) return score
            window *= 2
            when {
                score <= alpha -> alpha = maxOf(score - window, -Score.INFINITE) // Fail low: worse than expected
                score >= beta -> beta = minOf(score + window, Score.INFINITE) // Fail high: better than expected
                else -> return score
            }
        }
    }

    private fun negamax(depth: Int, ply: Int, alphaStart: Int, betaStart: Int, onPv: Boolean, allowNullMove: Boolean = true): Int {
        pvLength[ply] = ply
        var alpha = alphaStart
        var beta = betaStart
        val pvNode = betaStart - alphaStart > 1

        if (ply > 0) {
            if (isDraw()) {
                return drawScore()
            }
            // Mate distance pruning: no line from here can be better than mating in the next move
            alpha = maxOf(alpha, Score.matedIn(ply))
            beta = minOf(beta, Score.mateIn(ply + 1))
            if (alpha >= beta) {
                return alpha
            }
        }

        if (depth <= 0) {
            return quiescence(ply, alpha, beta)
        }
        if (ply >= MAX_PLY) {
            return evaluate()
        }

        countNode()
        if (aborted) return 0

        // A previous search of this position at least as deep can answer without searching
        // (not in the principal variation, to keep it complete and exact)
        val entry = transpositionTable?.probe(position.key) ?: TranspositionTable.NO_ENTRY
        if (!pvNode && entry != TranspositionTable.NO_ENTRY && TranspositionTable.depthOf(entry) >= depth) {
            val score = TranspositionTable.scoreFromTable(TranspositionTable.scoreOf(entry), ply)
            when (TranspositionTable.boundOf(entry)) {
                TranspositionTable.EXACT -> return score
                TranspositionTable.LOWER_BOUND -> if (score >= beta) return score
                TranspositionTable.UPPER_BOUND -> if (score <= alpha) return score
            }
        }

        val inCheck = position.inCheck()
        val staticEval = if (inCheck) -Score.INFINITE else evaluate()
        val canPrune = !pvNode && !inCheck && !Score.isMate(alpha) && !Score.isMate(beta)

        // Reverse futility pruning: even losing a margin per remaining ply, the position stays above beta
        if (canPrune && depth <= REVERSE_FUTILITY_MAX_DEPTH && staticEval - REVERSE_FUTILITY_MARGIN * depth >= beta) {
            return staticEval
        }

        if (canPrune && allowNullMove && depth >= NULL_MOVE_MIN_DEPTH && position.lastMove() != Move.NONE &&
            position.hasNonPawnMaterial(position.sideToMove) && staticEval >= beta) {
            val reduction = NULL_MOVE_REDUCTION + depth / 4
            position.makeNullMove()
            var score = -negamax(depth - 1 - reduction, ply + 1, -beta, -beta + 1, onPv = false)
            position.unmakeNullMove()
            if (aborted) return 0
            if (score >= beta) {
                if (Score.isMate(score)) score = beta // Do not trust mates found after passing
                if (depth < NULL_MOVE_VERIFICATION_DEPTH && pieceCount(position.sideToMove) > NULL_MOVE_VERIFICATION_PIECES) {
                    return score
                }
                // Verification against zugzwang: the same position without null move must also fail high
                val verified = negamax(depth - 1 - reduction, ply, beta - 1, beta, onPv = false, allowNullMove = false)
                if (aborted) return 0
                if (verified >= beta) return score
            }
        }

        val moves = moveLists[ply]
        MoveGenerator.generatePseudoLegal(position, moves)
        val pvMove = if (onPv && ply < previousPv.size) Move(previousPv[ply]) else Move.NONE
        val tableMove = if (entry != TranspositionTable.NO_ENTRY) TranspositionTable.moveOf(entry) else Move.NONE
        scoreMoves(moves, ply, pvMove, tableMove)

        val alphaOriginal = alpha
        var best = -Score.INFINITE
        var bestMove = Move.NONE
        var legalMoves = 0
        var quiets = 0

        for (i in 0 until moves.size) {
            val move = nextMove(moves, ply, i)
            if (ply == 0 && limits.searchMoves.isNotEmpty() && move !in limits.searchMoves) {
                continue
            }

            position.makeMove(move)
            if (position.leftKingInCheck()) {
                position.unmakeMove(move)
                continue
            }
            legalMoves++
            val childOnPv = onPv && move == pvMove
            val givesCheck = position.inCheck()

            if (canPrune && legalMoves > 1 && isQuiet(move) && !givesCheck && !isKiller(move, ply)) {
                // Late move pruning: late quiet moves near the leaves are very unlikely to be good
                // Futility pruning: a quiet move cannot raise the static evaluation enough to reach alpha
                if ((depth <= LATE_MOVE_PRUNING_MAX_DEPTH && legalMoves > LATE_MOVE_PRUNING_BASE + depth * depth) ||
                    (depth <= FUTILITY_MAX_DEPTH && staticEval + FUTILITY_MARGIN * depth <= alpha)) {
                    position.unmakeMove(move)
                    quietsSearched[ply][quiets++] = move.bits
                    continue
                }
            }

            // Check extension: a safe check (the checking piece cannot be captured) is searched one ply deeper
            val extension = if (givesCheck && ply < rootDepth * 2 && !position.isAttacked(move.to, position.sideToMove)) 1 else 0
            val newDepth = depth - 1 + extension
            var score: Int
            if (legalMoves == 1) {
                score = -negamax(newDepth, ply + 1, -beta, -alpha, childOnPv)
            } else {
                val reduction = if (depth >= LMR_MIN_DEPTH && isQuiet(move) && !inCheck && !givesCheck && !isKiller(move, ply)) {
                    lateMoveReduction(depth, legalMoves, pvNode)
                } else 0
                // Null window: is this move better than the best so far?
                score = -negamax(newDepth - reduction, ply + 1, -alpha - 1, -alpha, childOnPv)
                if (reduction > 0 && score > alpha) {
                    score = -negamax(newDepth, ply + 1, -alpha - 1, -alpha, childOnPv) // Without reduction
                }
                if (score > alpha && score < beta) {
                    score = -negamax(newDepth, ply + 1, -beta, -alpha, childOnPv) // It is: search its exact score
                }
            }
            position.unmakeMove(move)

            if (aborted) return 0

            if (score > best) {
                best = score
                if (score > alpha) {
                    alpha = score
                    bestMove = move
                    updatePv(ply, move)
                    if (alpha >= beta) {
                        if (isQuiet(move)) {
                            updateQuietHistory(move, ply, depth, quiets)
                        }
                        break // The opponent will avoid this position: no need to search other moves
                    }
                }
            }
            if (isQuiet(move)) {
                quietsSearched[ply][quiets++] = move.bits
            }
        }

        if (legalMoves == 0) {
            return if (inCheck) Score.matedIn(ply) else drawScore()
        }

        val bound = when {
            best >= beta -> TranspositionTable.LOWER_BOUND
            best > alphaOriginal -> TranspositionTable.EXACT
            else -> TranspositionTable.UPPER_BOUND
        }
        transpositionTable?.store(position.key, bestMove, TranspositionTable.scoreToTable(best, ply), depth, bound)
        return best
    }

    /** Searches captures and promotions (or every move when in check) until the position is quiet. */
    private fun quiescence(ply: Int, alphaStart: Int, beta: Int): Int {
        pvLength[ply] = ply
        countNode()
        if (aborted) return 0
        selectiveDepth = maxOf(selectiveDepth, ply)

        if (ply >= MAX_PLY) {
            return evaluate()
        }

        var alpha = alphaStart
        val inCheck = position.inCheck()
        var best = -Score.INFINITE
        var standPat = -Score.INFINITE

        if (!inCheck) {
            // Stand pat: the side to move can usually do at least as well as the static evaluation, by a quiet move
            standPat = evaluate()
            if (standPat >= beta) {
                return standPat
            }
            alpha = maxOf(alpha, standPat)
            best = standPat
        }

        val moves = moveLists[ply]
        MoveGenerator.generatePseudoLegal(position, moves, tacticalOnly = !inCheck)
        scoreMoves(moves, ply, Move.NONE, Move.NONE)
        var legalMoves = 0

        for (i in 0 until moves.size) {
            val move = nextMove(moves, ply, i)

            // Delta pruning: even winning the captured piece for free would not reach alpha
            if (!inCheck && !move.isPromotion && standPat + capturedValue(move) + DELTA_MARGIN < alpha) {
                continue
            }
            // Captures that lose material after the recaptures
            if (!inCheck && !move.isPromotion && isLosingCapture(move)) {
                continue
            }

            position.makeMove(move)
            if (position.leftKingInCheck()) {
                position.unmakeMove(move)
                continue
            }
            legalMoves++
            val score = -quiescence(ply + 1, -beta, -alpha)
            position.unmakeMove(move)

            if (aborted) return 0

            if (score > best) {
                best = score
                if (score > alpha) {
                    alpha = score
                    updatePv(ply, move)
                    if (alpha >= beta) {
                        break
                    }
                }
            }
        }

        if (inCheck && legalMoves == 0) {
            return Score.matedIn(ply)
        }
        return best
    }

    /** Static evaluation, scaled down as the fifty-move counter grows (no progress: closer to a draw). */
    private fun evaluate(): Int = evaluator.evaluate(position) * (PROGRESS_SCALE - position.halfmoveClock) / PROGRESS_SCALE

    /** Knights, bishops, rooks and queens of [color]. */
    private fun pieceCount(color: Int): Int =
        popCount(position.pieces(color, KNIGHT) or position.pieces(color, BISHOP) or position.pieces(color, ROOK) or position.pieces(color, QUEEN))

    /** Score of a draw for the side to move: the engine (root color) dislikes draws by its contempt. */
    private fun drawScore(): Int = if (position.sideToMove == rootColor) -evaluator.contempt else evaluator.contempt

    /** Repetition (once is enough inside the search), fifty-move rule or insufficient material. */
    private fun isDraw(): Boolean =
        position.halfmoveClock >= 100 || position.repetitions() >= 1 || position.isInsufficientMaterial()

    private fun countNode() {
        nodes++
        if (!canAbort) return
        if (limits.nodes != null && nodes >= limits.nodes!!) {
            aborted = true
        } else if (nodes and 1023L == 0L && (stop.get() || timeManager.mustAbort(elapsed()))) {
            aborted = true
        }
    }

    private fun elapsed() = System.currentTimeMillis() - startTime

    private fun updatePv(ply: Int, move: Move) {
        pv[ply][ply] = move.bits
        for (next in ply + 1 until pvLength[ply + 1]) {
            pv[ply][next] = pv[ply + 1][next]
        }
        pvLength[ply] = maxOf(pvLength[ply + 1], ply + 1)
    }

    override fun clear() {
        transpositionTable?.clear()
        clearHistory()
    }

    fun clearHistory() {
        history.forEach { it.fill(0) }
        counterMoves.fill(Move.NONE.bits)
    }

    /** Index in [counterMoves] of the last move (the piece moved and its destination), or -1 after a null move. */
    private fun counterMoveIndex(): Int {
        val last = position.lastMove()
        if (last == Move.NONE) return -1
        val piece = position.pieceOn(last.to)
        return if (piece == NO_PIECE) -1 else piece * 64 + last.to
    }

    private fun isQuiet(move: Move) = !move.isCapture && !move.isPromotion

    private fun isKiller(move: Move, ply: Int) = move.bits == killers[ply][0] || move.bits == killers[ply][1]

    /** Reduction of the [moveNumber]-th move: more for later moves and deeper searches, less in the principal variation. */
    private fun lateMoveReduction(depth: Int, moveNumber: Int, pvNode: Boolean): Int {
        val reduction = LMR_TABLE[minOf(depth, LMR_TABLE.size - 1)][minOf(moveNumber, MoveList.MAX_MOVES - 1)] - if (pvNode) 1 else 0
        return reduction.coerceIn(0, depth - 2)
    }

    /** A quiet move caused a cutoff: it becomes a killer, its history increases and the history of the quiet moves before decreases. */
    private fun updateQuietHistory(move: Move, ply: Int, depth: Int, quietsBefore: Int) {
        val plyKillers = killers[ply]
        if (plyKillers[0] != move.bits) {
            plyKillers[1] = plyKillers[0]
            plyKillers[0] = move.bits
        }
        val counterIndex = counterMoveIndex()
        if (counterIndex >= 0) counterMoves[counterIndex] = move.bits
        val bonus = minOf(depth * depth, MAX_HISTORY_BONUS)
        val colorHistory = history[position.sideToMove]
        addHistory(colorHistory, move, bonus)
        for (i in 0 until quietsBefore) {
            addHistory(colorHistory, Move(quietsSearched[ply][i]), -bonus)
        }
    }

    /** History gravity: values tend to stay within [-MAX_HISTORY, MAX_HISTORY]. */
    private fun addHistory(colorHistory: IntArray, move: Move, bonus: Int) {
        val index = move.from * 64 + move.to
        colorHistory[index] += bonus - colorHistory[index] * Math.abs(bonus) / MAX_HISTORY
    }

    /** Old history is less relevant: halve it at each search. */
    private fun agingHistory() {
        for (colorHistory in history) {
            for (i in colorHistory.indices) colorHistory[i] /= 2
        }
    }

    private fun scoreMoves(moves: MoveList, ply: Int, pvMove: Move, tableMove: Move) {
        val scores = orderScores[ply]
        val counterIndex = counterMoveIndex()
        val counterMove = if (counterIndex >= 0) counterMoves[counterIndex] else Move.NONE.bits
        for (i in 0 until moves.size) {
            val move = moves[i]
            scores[i] = when {
                move == tableMove -> TABLE_MOVE_SCORE
                move == pvMove -> PV_MOVE_SCORE
                move.isCapture -> (if (isLosingCapture(move)) BAD_CAPTURE_SCORE else CAPTURE_SCORE) +
                    capturedValue(move) * 10 - attackerOrder(move) +
                    if (move.isPromotion && move.promotionType == QUEEN) QUEEN_PROMOTION_SCORE else 0
                move.isPromotion -> if (move.promotionType == QUEEN) QUEEN_PROMOTION_SCORE else UNDERPROMOTION_SCORE
                move.bits == killers[ply][0] -> FIRST_KILLER_SCORE
                move.bits == killers[ply][1] -> SECOND_KILLER_SCORE
                move.bits == counterMove -> COUNTER_MOVE_SCORE
                else -> history[position.sideToMove][move.from * 64 + move.to]
            }
        }
    }

    /** Selection sort, one move at a time: after a cutoff the remaining moves are not sorted. */
    private fun nextMove(moves: MoveList, ply: Int, index: Int): Move {
        val scores = orderScores[ply]
        var best = index
        for (i in index + 1 until moves.size) {
            if (scores[i] > scores[best]) best = i
        }
        if (best != index) {
            moves.swap(index, best)
            val score = scores[index]
            scores[index] = scores[best]
            scores[best] = score
        }
        return moves[index]
    }

    private fun capturedValue(move: Move): Int {
        if (move.isEnPassant) return PIECE_VALUES[PAWN]
        val captured = position.pieceOn(move.to)
        return if (captured == NO_PIECE) 0 else PIECE_VALUES[typeOf(captured)]
    }

    /** Whether a capture loses material after the recaptures. Captures of a more valuable piece never lose. */
    private fun isLosingCapture(move: Move): Boolean =
        PIECE_VALUES[attackerOrder(move)] > capturedValue(move) && !StaticExchange.isAtLeast(position, move, 0)

    /** Least valuable attackers first: pawn 0, ..., king 5. */
    private fun attackerOrder(move: Move): Int = typeOf(position.pieceOn(move.from))

    companion object {
        const val MAX_PLY = 128
        const val MAX_DEPTH = 100

        private const val NULL_MOVE_MIN_DEPTH = 3
        private const val NULL_MOVE_REDUCTION = 3
        /** Null move cutoffs are verified in searches at least this deep... */
        private const val NULL_MOVE_VERIFICATION_DEPTH = 10
        /** ...and when the side to move has at most this number of pieces (zugzwang is more likely). */
        private const val NULL_MOVE_VERIFICATION_PIECES = 2

        private const val REVERSE_FUTILITY_MAX_DEPTH = 6
        private const val REVERSE_FUTILITY_MARGIN = 80
        private const val FUTILITY_MAX_DEPTH = 3
        private const val FUTILITY_MARGIN = 120
        private const val LATE_MOVE_PRUNING_MAX_DEPTH = 3
        private const val LATE_MOVE_PRUNING_BASE = 4

        private const val LMR_MIN_DEPTH = 3

        /** LMR_TABLE[depth][moveNumber] = 0.75 + ln(depth) * ln(moveNumber) / 2.25 */
        private val LMR_TABLE = Array(MAX_DEPTH + 1) { depth ->
            IntArray(MoveList.MAX_MOVES) { moveNumber ->
                if (depth == 0 || moveNumber == 0) 0 else (0.75 + Math.log(depth.toDouble()) * Math.log(moveNumber.toDouble()) / 2.25).toInt()
            }
        }

        private const val ASPIRATION_MIN_DEPTH = 5
        private const val ASPIRATION_WINDOW = 25

        private const val TABLE_MOVE_SCORE = 2_000_000
        private const val PV_MOVE_SCORE = 1_000_000
        private const val CAPTURE_SCORE = 100_000
        private const val QUEEN_PROMOTION_SCORE = 90_000
        private const val FIRST_KILLER_SCORE = 80_000
        private const val SECOND_KILLER_SCORE = 79_000
        private const val COUNTER_MOVE_SCORE = 78_000
        /** Quiet moves are ordered by their history, between -MAX_HISTORY and MAX_HISTORY. */
        private const val MAX_HISTORY = 16_384
        private const val MAX_HISTORY_BONUS = 1_200
        private const val UNDERPROMOTION_SCORE = -MAX_HISTORY - 1
        private const val BAD_CAPTURE_SCORE = -100_000

        /** The evaluation is multiplied by (PROGRESS_SCALE - halfmove clock) / PROGRESS_SCALE: halved at the fifty-move rule. */
        private const val PROGRESS_SCALE = 200

        /** Margin of the delta pruning in the quiescence search (positional gains of a capture). */
        private const val DELTA_MARGIN = 200

        /** Piece values for move ordering and delta pruning, indexed by piece type. */
        private val PIECE_VALUES = intArrayOf(100, 320, 330, 500, 900, 0).also { require(it.size == KING + 1) }
    }
}
