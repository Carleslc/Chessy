package chessy.search

import chessy.core.KING
import chessy.core.Move
import chessy.core.MoveGenerator
import chessy.core.MoveList
import chessy.core.NO_PIECE
import chessy.core.PAWN
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.typeOf
import chessy.eval.Evaluator
import chessy.eval.MaterialEvaluator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Negamax with alpha-beta pruning (https://www.chessprogramming.org/Alpha-Beta), the main search of Chessy:
 *
 * - Iterative deepening: searches depth 1, 2, 3... while there is time, so the depth adapts to each position
 *   (deeper in endgames, with fewer moves) and the best move of the previous iteration is searched first.
 * - Quiescence search: at the end of the main search, captures and promotions are searched until the position is quiet,
 *   so the evaluation is not fooled by a pending capture (horizon effect).
 * - Move ordering: principal variation first, then captures by MVV-LVA (most valuable victim, least valuable attacker),
 *   then promotions, then quiet moves. Good moves first produce more cutoffs.
 * - Checkmates are scored by distance (shorter mates are preferred) and draws by repetition, fifty-move rule
 *   and insufficient material are detected inside the search.
 *
 * Scores are always from the point of view of the side to move (negamax: the score of a move is the negated score
 * of the position after it for the opponent).
 */
class AlphaBetaSearch(var evaluator: Evaluator = MaterialEvaluator()) : Search {

    // Preallocated per ply, to avoid allocations during the search
    private val moveLists = Array(MAX_PLY + 1) { MoveList() }
    private val orderScores = Array(MAX_PLY + 1) { IntArray(MoveList.MAX_MOVES) }

    /** Triangular principal variation table: pv[ply] is the best line found from that ply. */
    private val pv = Array(MAX_PLY + 1) { IntArray(MAX_PLY + 1) }
    private val pvLength = IntArray(MAX_PLY + 1)

    /** Principal variation of the previous iteration, searched first. */
    private var previousPv = IntArray(0)

    private lateinit var position: Position
    private lateinit var limits: SearchLimits
    private lateinit var stop: AtomicBoolean
    private lateinit var timeManager: TimeManager
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
        timeManager = TimeManager(limits, position.sideToMove)
        startTime = System.currentTimeMillis()
        nodes = 0
        selectiveDepth = 0
        aborted = false
        previousPv = IntArray(0)

        val rootMoves = MoveGenerator.legalMoves(position).toList().filter { limits.searchMoves.isEmpty() || it in limits.searchMoves }
        require(rootMoves.isNotEmpty()) { "No legal moves to search" }

        var result = SearchResult(rootMoves.first(), 0)
        val maxDepth = minOf(limits.depth ?: MAX_DEPTH, MAX_DEPTH)

        for (depth in 1..maxDepth) {
            canAbort = depth > 1
            selectiveDepth = 0
            val score = negamax(depth, 0, -Score.INFINITE, Score.INFINITE, onPv = true)
            if (aborted) {
                break // The previous iteration is the last complete one
            }

            val line = (0 until pvLength[0]).map { Move(pv[0][it]) }
            previousPv = IntArray(line.size) { line[it].bits }
            result = SearchResult(line.first(), score, line.getOrNull(1) ?: Move.NONE)
            listener(SearchInfo(depth, maxOf(depth, selectiveDepth), score, nodes, elapsed(), line))

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

    private fun negamax(depth: Int, ply: Int, alphaStart: Int, betaStart: Int, onPv: Boolean): Int {
        pvLength[ply] = ply
        var alpha = alphaStart
        var beta = betaStart

        if (ply > 0) {
            if (isDraw()) {
                return Score.DRAW
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
            return evaluator.evaluate(position)
        }

        countNode()
        if (aborted) return 0

        val inCheck = position.inCheck()
        val moves = moveLists[ply]
        MoveGenerator.generatePseudoLegal(position, moves)
        val pvMove = if (onPv && ply < previousPv.size) Move(previousPv[ply]) else Move.NONE
        scoreMoves(moves, ply, pvMove)

        var best = -Score.INFINITE
        var legalMoves = 0

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
            val score = -negamax(depth - 1, ply + 1, -beta, -alpha, onPv && move == pvMove)
            position.unmakeMove(move)

            if (aborted) return 0

            if (score > best) {
                best = score
                if (score > alpha) {
                    alpha = score
                    updatePv(ply, move)
                    if (alpha >= beta) {
                        break // The opponent will avoid this position: no need to search other moves
                    }
                }
            }
        }

        if (legalMoves == 0) {
            return if (inCheck) Score.matedIn(ply) else Score.DRAW
        }
        return best
    }

    /** Searches captures and promotions (or every move when in check) until the position is quiet. */
    private fun quiescence(ply: Int, alphaStart: Int, beta: Int): Int {
        pvLength[ply] = ply
        countNode()
        if (aborted) return 0
        selectiveDepth = maxOf(selectiveDepth, ply)

        if (ply >= MAX_PLY) {
            return evaluator.evaluate(position)
        }

        var alpha = alphaStart
        val inCheck = position.inCheck()
        var best = -Score.INFINITE
        var standPat = -Score.INFINITE

        if (!inCheck) {
            // Stand pat: the side to move can usually do at least as well as the static evaluation, by a quiet move
            standPat = evaluator.evaluate(position)
            if (standPat >= beta) {
                return standPat
            }
            alpha = maxOf(alpha, standPat)
            best = standPat
        }

        val moves = moveLists[ply]
        MoveGenerator.generatePseudoLegal(position, moves, tacticalOnly = !inCheck)
        scoreMoves(moves, ply, Move.NONE)
        var legalMoves = 0

        for (i in 0 until moves.size) {
            val move = nextMove(moves, ply, i)

            // Delta pruning: even winning the captured piece for free would not reach alpha
            if (!inCheck && !move.isPromotion && standPat + capturedValue(move) + DELTA_MARGIN < alpha) {
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

    private fun scoreMoves(moves: MoveList, ply: Int, pvMove: Move) {
        val scores = orderScores[ply]
        for (i in 0 until moves.size) {
            val move = moves[i]
            scores[i] = when {
                move == pvMove -> PV_MOVE_SCORE
                move.isCapture -> CAPTURE_SCORE + capturedValue(move) * 10 - attackerOrder(move) +
                    if (move.isPromotion && move.promotionType == QUEEN) QUEEN_PROMOTION_SCORE else 0
                move.isPromotion -> if (move.promotionType == QUEEN) QUEEN_PROMOTION_SCORE else UNDERPROMOTION_SCORE
                else -> 0
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

    /** Least valuable attackers first: pawn 0, ..., king 5. */
    private fun attackerOrder(move: Move): Int = typeOf(position.pieceOn(move.from))

    companion object {
        const val MAX_PLY = 128
        const val MAX_DEPTH = 100

        private const val PV_MOVE_SCORE = 1_000_000
        private const val CAPTURE_SCORE = 100_000
        private const val QUEEN_PROMOTION_SCORE = 90_000
        private const val UNDERPROMOTION_SCORE = -1

        /** Margin of the delta pruning in the quiescence search (positional gains of a capture). */
        private const val DELTA_MARGIN = 200

        /** Piece values for move ordering and delta pruning, indexed by piece type. */
        private val PIECE_VALUES = intArrayOf(100, 320, 330, 500, 900, 0).also { require(it.size == KING + 1) }
    }
}
