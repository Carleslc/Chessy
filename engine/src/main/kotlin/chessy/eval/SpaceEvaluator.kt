package chessy.eval

import chessy.core.Attacks
import chessy.core.BISHOP
import chessy.core.BLACK
import chessy.core.Bitboard
import chessy.core.Color
import chessy.core.EMPTY
import chessy.core.KING
import chessy.core.KNIGHT
import chessy.core.PAWN
import chessy.core.Position
import chessy.core.QUEEN
import chessy.core.ROOK
import chessy.core.Square
import chessy.core.WHITE
import chessy.core.bitboardOf
import chessy.core.contains
import chessy.core.fileMask
import chessy.core.fileOf
import chessy.core.flipRank
import chessy.core.forEachSquare
import chessy.core.opposite
import chessy.core.pieceChar
import chessy.core.popCount
import chessy.core.rankMask
import chessy.core.rankOf
import chessy.core.relativeRank
import chessy.core.squareName

/**
 * "Constrictor" evaluation: space, square control and mobility first.
 *
 * The value of a piece depends on its activity: material and piece-square tables are complemented by its safe mobility,
 * outposts and open files, and penalties when it is trapped. Space (safe squares behind the own pawns),
 * territory (squares controlled in the half of the opponent) and restriction (squares taken from the opponent)
 * reward the positions where the opponent runs out of useful moves. Threats, king safety and pawn structure
 * keep the play sound. See the PRD (5.3) for the ideas behind each term.
 *
 * Not thread-safe: it reuses internal arrays, so each search thread needs its own instance.
 */
class SpaceEvaluator(val weights: SpaceWeights = SpaceWeights.DEFAULT, override val name: String = "Space") : Evaluator {

    override val contempt: Int get() = weights.contempt

    /** Terms of the evaluation, in the order they are shown by [explain]. */
    private enum class Term(val label: String) {
        MATERIAL("Material"),
        PIECE_SQUARES("Piece squares"),
        MOBILITY("Mobility"),
        TRAPPED("Trapped pieces"),
        WORST_PIECE("Worst piece"),
        SPACE("Space"),
        TERRITORY("Territory"),
        RESTRICTION("Restriction"),
        OUTPOSTS("Outposts"),
        ROOKS("Rook files"),
        PAWNS("Pawn structure"),
        PASSED_PAWNS("Passed pawns"),
        WEAKNESSES("Overextension, holes"),
        THREATS("Threats"),
        KING_SAFETY("King safety"),
        BISHOP_PAIR("Bishop pair"),
        TEMPO("Tempo"),
    }

    // Scores of each term and color: index term.ordinal * 2 + color
    private val middlegame = IntArray(Term.entries.size * 2)
    private val endgame = IntArray(Term.entries.size * 2)

    // Attack maps of the position, by color
    private val attackedBy = Array(2) { LongArray(KING + 1) }
    private val attacked = LongArray(2)
    private val attackedTwice = LongArray(2)
    private val mobilityArea = LongArray(2)

    /** Dynamic value of each piece, only collected by [explain]. */
    private var pieceValues: MutableList<EvalTrace.PieceValue>? = null

    override fun evaluate(position: Position): Int {
        compute(position)
        return total(position)
    }

    override fun explain(position: Position): EvalTrace {
        val pieces = mutableListOf<EvalTrace.PieceValue>()
        pieceValues = pieces
        try {
            compute(position)
        } finally {
            pieceValues = null
        }
        return EvalTrace(name, GamePhase.of(position), position.sideToMove).apply {
            for (term in Term.entries) {
                add(term.label, score(term, WHITE), score(term, BLACK))
            }
            this.pieces.addAll(pieces)
        }
    }

    private fun score(term: Term, color: Color) =
        TaperedScore(middlegame[term.ordinal * 2 + color], endgame[term.ordinal * 2 + color])

    private fun total(position: Position): Int {
        var mg = 0
        var eg = 0
        for (i in middlegame.indices step 2) {
            mg += middlegame[i] - middlegame[i + 1]
            eg += endgame[i] - endgame[i + 1]
        }
        val white = TaperedScore(mg, eg).taper(GamePhase.of(position))
        return if (position.sideToMove == WHITE) white else -white
    }

    private fun add(term: Term, color: Color, middlegameScore: Int, endgameScore: Int) {
        middlegame[term.ordinal * 2 + color] += middlegameScore
        endgame[term.ordinal * 2 + color] += endgameScore
    }

    private fun add(term: Term, color: Color, score: TaperedScore, times: Int = 1) =
        add(term, color, score.middlegame * times, score.endgame * times)

    private fun compute(position: Position) {
        middlegame.fill(0)
        endgame.fill(0)
        computeKingAndPawnAttacks(position)
        for (color in WHITE..BLACK) {
            evaluatePieces(position, color)
        }
        for (color in WHITE..BLACK) {
            evaluatePawns(position, color)
            evaluateSpace(position, color)
            evaluateControl(position, color)
            evaluateThreats(position, color)
            evaluateKingSafety(position, color)
            if (popCount(position.pieces(color, BISHOP)) >= 2) add(Term.BISHOP_PAIR, color, weights.bishopPair)
        }
        add(Term.TEMPO, position.sideToMove, weights.tempo)
    }

    private fun computeKingAndPawnAttacks(position: Position) {
        for (color in WHITE..BLACK) {
            attackedBy[color].fill(EMPTY)
            val pawns = position.pieces(color, PAWN)
            val pawnAttacks = EvalMasks.pawnAttacks(color, pawns)
            val kingAttacks = Attacks.kingAttacks(position.kingSquare(color))
            attackedBy[color][PAWN] = pawnAttacks
            attackedBy[color][KING] = kingAttacks
            attackedTwice[color] = EvalMasks.pawnDoubleAttacks(color, pawns) or (pawnAttacks and kingAttacks)
            attacked[color] = pawnAttacks or kingAttacks
        }
        for (color in WHITE..BLACK) {
            // Squares where the pieces can move safely: not blocked by the own pawns or king, nor attacked by enemy pawns
            mobilityArea[color] = (position.pieces(color, PAWN) or position.pieces(color, KING) or attackedBy[opposite(color)][PAWN]).inv()
        }
    }

    /** Material, piece-square tables, mobility, trapped pieces, outposts and rooks. Also the attacks of the pieces. */
    private fun evaluatePieces(position: Position, color: Color) {
        val occupied = position.occupied
        var worstMobility: TaperedScore? = null

        for (type in PAWN..KING) {
            position.pieces(color, type).forEachSquare { square ->
                val index = if (color == WHITE) flipRank(square) else square
                val material = weights.pieceValues[type]
                val pstScale = if (type == PAWN || type == KING) 100 else weights.piecePstPercent
                var mg = material.middlegame + MaterialEvaluator.MIDDLEGAME_TABLES[type][index] * pstScale / 100
                var eg = material.endgame + MaterialEvaluator.ENDGAME_TABLES[type][index] * pstScale / 100
                add(Term.MATERIAL, color, material)
                add(Term.PIECE_SQUARES, color, mg - material.middlegame, eg - material.endgame)

                if (type in KNIGHT..QUEEN) {
                    val attacks = Attacks.attacks(type, square, occupied)
                    attackedTwice[color] = attackedTwice[color] or (attacked[color] and attacks)
                    attacked[color] = attacked[color] or attacks
                    attackedBy[color][type] = attackedBy[color][type] or attacks

                    val moves = popCount(attacks and mobilityArea[color])
                    val mobility = weights.mobility[type][minOf(moves, weights.mobility[type].size - 1)]
                    add(Term.MOBILITY, color, mobility)
                    mg += mobility.middlegame
                    eg += mobility.endgame
                    if (worstMobility == null || mobility.middlegame + mobility.endgame < worstMobility!!.middlegame + worstMobility!!.endgame) {
                        worstMobility = mobility
                    }

                    // Undeveloped pieces and rooks often have few moves, it is not a problem yet
                    if (moves <= weights.trappedMobility && type != ROOK && relativeRank(color, square) > 0) {
                        val trapped = weights.trapped[type]
                        add(Term.TRAPPED, color, trapped)
                        mg += trapped.middlegame
                        eg += trapped.endgame
                    }

                    if ((type == KNIGHT || type == BISHOP) && isOutpost(position, color, square)) {
                        val outpost = if (type == KNIGHT) weights.knightOutpost else weights.bishopOutpost
                        add(Term.OUTPOSTS, color, outpost)
                        mg += outpost.middlegame
                        eg += outpost.endgame
                    }

                    if (type == ROOK) {
                        val rook = rookBonus(position, color, square)
                        add(Term.ROOKS, color, rook)
                        mg += rook.middlegame
                        eg += rook.endgame
                    }

                    pieceValues?.add(EvalTrace.PieceValue(pieceChar(position.pieceOn(square)), squareName(square), TaperedScore(mg, eg), moves))
                }
            }
        }

        // A chain is as strong as its weakest link: the least active piece counts again
        worstMobility?.let { worst ->
            if (worst.middlegame + worst.endgame < 0) {
                add(Term.WORST_PIECE, color, worst.middlegame * weights.worstPiecePercent / 100, worst.endgame * weights.worstPiecePercent / 100)
            }
        }
    }

    /** A knight or bishop in the half of the opponent, protected by a pawn and out of reach of the enemy pawns. */
    private fun isOutpost(position: Position, color: Color, square: Square): Boolean =
        square in EvalMasks.OUTPOST_RANKS[color] &&
            (attackedBy[color][PAWN] and bitboardOf(square)) != EMPTY &&
            (EvalMasks.PAWN_ATTACK_SPAN[color][square] and position.pieces(opposite(color), PAWN)) == EMPTY

    private fun rookBonus(position: Position, color: Color, square: Square): TaperedScore {
        val file = fileMask(fileOf(square))
        var score = when {
            (file and (position.pieces(WHITE, PAWN) or position.pieces(BLACK, PAWN))) == EMPTY -> weights.rookOpenFile
            (file and position.pieces(color, PAWN)) == EMPTY -> weights.rookSemiOpenFile
            else -> TaperedScore.ZERO
        }
        // Rook on the 7th rank, attacking pawns or confining the king to the last rank
        if (relativeRank(color, square) == 6) {
            val them = opposite(color)
            val seventh = rankMask(rankOf(square))
            if (relativeRank(color, position.kingSquare(them)) == 7 || (seventh and position.pieces(them, PAWN)) != EMPTY) {
                score += weights.rookOnSeventh
            }
        }
        return score
    }

    /** Doubled, isolated, connected and passed pawns, and weaknesses of advanced pawns (overextension, holes). */
    private fun evaluatePawns(position: Position, color: Color) {
        val them = opposite(color)
        val pawns = position.pieces(color, PAWN)
        val enemyPawns = position.pieces(them, PAWN)
        val ownKing = position.kingSquare(color)
        val enemyKing = position.kingSquare(them)

        pawns.forEachSquare { square ->
            val file = fileOf(square)
            val rank = relativeRank(color, square)
            val supported = (attackedBy[color][PAWN] and bitboardOf(square)) != EMPTY
            val phalanx = (EvalMasks.ADJACENT_FILES[file] and rankMask(rankOf(square)) and pawns) != EMPTY
            val blockedByOwn = (EvalMasks.FORWARD_FILE[color][square] and pawns) != EMPTY

            if (blockedByOwn) add(Term.PAWNS, color, weights.doubledPawn)
            if ((EvalMasks.ADJACENT_FILES[file] and pawns) == EMPTY) add(Term.PAWNS, color, weights.isolatedPawn)
            if (supported || phalanx) add(Term.PAWNS, color, weights.connectedPawn[rank])

            val passed = !blockedByOwn && (EvalMasks.PASSED_PAWN[color][square] and enemyPawns) == EMPTY
            if (passed) {
                var bonus = weights.passedPawn[rank]
                val stop = if (color == WHITE) square + 8 else square - 8
                if (stop in 0..63 && (position.occupied and bitboardOf(stop)) != EMPTY) {
                    bonus = TaperedScore(bonus.middlegame / 2, bonus.endgame / 2) // Blocked
                }
                if (stop in 0..63 && rank >= 3) {
                    // In the endgame the kings matter: the enemy king should be far and the own king close
                    val kings = (EvalMasks.distance(enemyKing, stop) * weights.passedEnemyKingDistance - EvalMasks.distance(ownKing, stop) * weights.passedOwnKingDistance) * (rank - 2)
                    bonus += TaperedScore(0, kings)
                }
                add(Term.PASSED_PAWNS, color, bonus)
            } else if (rank >= 4 && !supported && !phalanx) {
                // Overextension: an advanced pawn without support is a target, not space
                add(Term.WEAKNESSES, color, weights.overextendedPawn)
            }
        }

        // Holes: central squares of the own camp that the own pawns can no longer protect, if the opponent has minor pieces
        val enemyMinors = position.pieces(them, KNIGHT) or position.pieces(them, BISHOP)
        if (enemyMinors != EMPTY) {
            var holes = 0
            (EvalMasks.CENTER_FILES and EvalMasks.SPACE_RANKS[color] and pawns.inv()).forEachSquare { square ->
                if (relativeRank(color, square) >= 2 && (EvalMasks.PAWN_ATTACK_SPAN[them][square] and pawns) == EMPTY) holes++
            }
            add(Term.WEAKNESSES, color, weights.hole, holes)
        }
    }

    /**
     * Space: safe squares for the minor pieces in the central files of the own camp (ranks 2 to 4),
     * counting twice those behind the own pawns. It matters more with many pieces on the board
     * (the cramped side wants exchanges), so its weight grows with the pieces and the blocked pawns.
     */
    private fun evaluateSpace(position: Position, color: Color) {
        if (nonPawnMaterial(position) < weights.spaceMaterialThreshold) return
        val them = opposite(color)
        val pawns = position.pieces(color, PAWN)
        val safe = EvalMasks.CENTER_FILES and EvalMasks.SPACE_RANKS[color] and pawns.inv() and attackedBy[them][PAWN].inv()
        var behind = pawns
        behind = behind or EvalMasks.backward(color, behind)
        behind = behind or EvalMasks.backward(color, EvalMasks.backward(color, behind))
        val bonus = popCount(safe) + popCount(behind and safe and attacked[them].inv())

        val blockedPawns = popCount(EvalMasks.forward(WHITE, position.pieces(WHITE, PAWN)) and position.pieces(BLACK, PAWN)) * 2
        val weight = popCount(position.occupancy(color)) - 3 + minOf(blockedPawns, 9)
        add(Term.SPACE, color, bonus * weight * weight * weights.spacePercent / 1600, 0)
    }

    /** Territory (squares controlled in the half of the opponent and around its king) and restriction of its pieces. */
    private fun evaluateControl(position: Position, color: Color) {
        val them = opposite(color)
        // Controlled: we attack it, the enemy pawns do not, and the opponent does not attack it more times
        val controlled = attacked[color] and attackedBy[them][PAWN].inv() and (attackedTwice[color] or attacked[them].inv())
        add(Term.TERRITORY, color, weights.territory, popCount(controlled and EvalMasks.ENEMY_HALF[color]))
        add(Term.TERRITORY, color, weights.kingZoneControl, popCount(controlled and EvalMasks.KING_ZONE[position.kingSquare(them)]))

        // Restriction: squares the opponent attacks but does not hold firmly, which we also attack
        val stronglyProtected = attackedBy[them][PAWN] or (attackedTwice[them] and attackedTwice[color].inv())
        val restricted = attacked[them] and stronglyProtected.inv() and attacked[color]
        add(Term.RESTRICTION, color, weights.restriction, popCount(restricted))
    }

    /** Enemy pieces attacked by pawns or by less valuable pieces, and hanging pieces (attacked and not defended). */
    private fun evaluateThreats(position: Position, color: Color) {
        val them = opposite(color)
        val enemyPieces = position.occupancy(them) and position.pieces(them, PAWN).inv() and position.pieces(them, KING).inv()
        val heavy = position.pieces(them, ROOK) or position.pieces(them, QUEEN)

        add(Term.THREATS, color, weights.threatByPawn, popCount(enemyPieces and attackedBy[color][PAWN]))
        add(Term.THREATS, color, weights.threatByMinor, popCount(heavy and (attackedBy[color][KNIGHT] or attackedBy[color][BISHOP])))
        add(Term.THREATS, color, weights.threatByRook, popCount(position.pieces(them, QUEEN) and attackedBy[color][ROOK]))
        add(Term.THREATS, color, weights.hangingPiece, popCount(enemyPieces and attacked[color] and attacked[them].inv()))
        add(Term.THREATS, color, weights.hangingPawn, popCount(position.pieces(them, PAWN) and attacked[color] and attacked[them].inv()))
    }

    /** Danger for the king of [color]: enemy attacks on its zone, safe checks, pawn shield and open files. */
    private fun evaluateKingSafety(position: Position, color: Color) {
        val them = opposite(color)
        val king = position.kingSquare(color)
        val zone = EvalMasks.KING_ZONE[king]
        val occupied = position.occupied

        var units = 2 * popCount(attackedBy[them][KNIGHT] and zone) +
            2 * popCount(attackedBy[them][BISHOP] and zone) +
            3 * popCount(attackedBy[them][ROOK] and zone) +
            5 * popCount(attackedBy[them][QUEEN] and zone)

        val safe = attacked[color].inv() and position.occupancy(them).inv()
        val diagonal = Attacks.bishopAttacks(king, occupied)
        val straight = Attacks.rookAttacks(king, occupied)
        units += 3 * popCount(Attacks.knightAttacks(king) and attackedBy[them][KNIGHT] and safe)
        units += 2 * popCount(diagonal and attackedBy[them][BISHOP] and safe)
        units += 3 * popCount(straight and attackedBy[them][ROOK] and safe)
        units += 4 * popCount((diagonal or straight) and attackedBy[them][QUEEN] and safe)

        if (position.pieces(them, QUEEN) != EMPTY || units >= weights.kingDangerMinUnits) {
            val danger = minOf(units * units * weights.kingDanger / 16, weights.maxKingDanger)
            add(Term.KING_SAFETY, color, -danger, -danger / 8)
        }

        // Pawn shield and open files in front of the king, while it stays in its camp
        if (relativeRank(color, king) <= 1) {
            val files = fileMask(fileOf(king)) or EvalMasks.ADJACENT_FILES[fileOf(king)]
            val shield = files and EvalMasks.SPACE_RANKS[color] and position.pieces(color, PAWN)
            add(Term.KING_SAFETY, color, weights.pawnShield, minOf(popCount(shield), 3))
            for (file in maxOf(0, fileOf(king) - 1)..minOf(7, fileOf(king) + 1)) {
                if ((fileMask(file) and position.pieces(color, PAWN)) == EMPTY) add(Term.KING_SAFETY, color, weights.openFileNearKing)
            }
        }
    }

    private fun nonPawnMaterial(position: Position): Int {
        var material = 0
        for (color in WHITE..BLACK) {
            for (type in KNIGHT..QUEEN) material += popCount(position.pieces(color, type)) * MaterialEvaluator.PIECE_VALUES[type].middlegame
        }
        return material
    }
}
