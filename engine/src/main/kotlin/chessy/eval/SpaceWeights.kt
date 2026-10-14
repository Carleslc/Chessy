package chessy.eval

import java.io.File
import kotlin.math.sqrt

/**
 * Weights of the "Constrictor" evaluation, in centipawns (middlegame, endgame).
 *
 * The values written here are the hand-chosen starting point. The weights used by default ([DEFAULT]) are read from
 * the file `space.weights` (resources), tuned with [chessy.tune.TexelTuner] on top of these values.
 * The style is given by the weights of space, territory, restriction and mobility ([STYLE], not tuned).
 */
data class SpaceWeights(
    /** Material values, indexed by piece type. */
    val pieceValues: Array<TaperedScore> = MaterialEvaluator.PIECE_VALUES.copyOf(),
    /** Percentage of the piece-square tables for knights, bishops, rooks and queens (mobility does most of the work). */
    val piecePstPercent: Int = 50,
    /** Percentage of the mobility tables (100 = baseline). */
    val mobilityPercent: Int = 120,
    /** Size of the mobility bonus of each piece type, see [mobility]. */
    val mobilityScale: Array<TaperedScore> = arrayOf(
        TaperedScore.ZERO, TaperedScore(30, 30), TaperedScore(30, 35), TaperedScore(20, 40), TaperedScore(12, 25), TaperedScore.ZERO,
    ),
    val trappedMobility: Int = 1,
    /** Penalty of knights, bishops and queens outside their first rank with at most [trappedMobility] safe moves. */
    val trapped: Array<TaperedScore> = arrayOf(
        TaperedScore.ZERO, TaperedScore(-25, -20), TaperedScore(-30, -25), TaperedScore.ZERO, TaperedScore(-30, -30), TaperedScore.ZERO,
    ),
    val worstPiecePercent: Int = 50,
    /** Percentage of the space term (100 = baseline). */
    val spacePercent: Int = 120,
    val spaceMaterialThreshold: Int = 3500,
    val territory: TaperedScore = TaperedScore(4, 2),
    val kingZoneControl: TaperedScore = TaperedScore(6, 2),
    val restriction: TaperedScore = TaperedScore(5, 5),
    val knightOutpost: TaperedScore = TaperedScore(30, 15),
    val bishopOutpost: TaperedScore = TaperedScore(15, 5),
    val rookOpenFile: TaperedScore = TaperedScore(35, 15),
    val rookSemiOpenFile: TaperedScore = TaperedScore(15, 8),
    val rookOnSeventh: TaperedScore = TaperedScore(20, 30),
    val doubledPawn: TaperedScore = TaperedScore(-10, -25),
    val isolatedPawn: TaperedScore = TaperedScore(-10, -15),
    /** By relative rank (0 = first rank). */
    val connectedPawn: Array<TaperedScore> = arrayOf(0, 3, 5, 8, 15, 30, 50, 0).map { TaperedScore(it, it) }.toTypedArray(),
    val passedPawn: Array<TaperedScore> = arrayOf(
        TaperedScore(0, 0), TaperedScore(5, 10), TaperedScore(8, 15), TaperedScore(15, 30),
        TaperedScore(30, 55), TaperedScore(55, 95), TaperedScore(90, 150), TaperedScore(0, 0),
    ),
    /** Endgame bonus of an advanced passed pawn for each square between its stop square and the enemy king. */
    val passedEnemyKingDistance: Int = 5,
    /** Endgame penalty of an advanced passed pawn for each square between its stop square and the own king. */
    val passedOwnKingDistance: Int = 2,
    val overextendedPawn: TaperedScore = TaperedScore(-10, -5),
    val hole: TaperedScore = TaperedScore(-5, 0),
    /**
     * Threats are bonuses for the attacker, counted on the pieces of the opponent: enemy pieces attacked by a pawn,
     * enemy rooks and queens attacked by a minor piece, an enemy queen attacked by a rook, and enemy pieces and pawns
     * that are attacked and not defended (hanging). Leaving an own piece hanging gives the bonus to the opponent.
     */
    val threatByPawn: TaperedScore = TaperedScore(60, 40),
    val threatByMinor: TaperedScore = TaperedScore(40, 30),
    val threatByRook: TaperedScore = TaperedScore(40, 30),
    val hangingPiece: TaperedScore = TaperedScore(40, 25),
    val hangingPawn: TaperedScore = TaperedScore(5, 10),
    val kingDanger: Int = 6,
    val kingDangerMinUnits: Int = 6,
    val maxKingDanger: Int = 500,
    val pawnShield: TaperedScore = TaperedScore(12, 0),
    val openFileNearKing: TaperedScore = TaperedScore(-15, 0),
    val bishopPair: TaperedScore = TaperedScore(30, 50),
    val tempo: TaperedScore = TaperedScore(12, 6),
    /** Penalty of a draw for the engine, in centipawns: a constrictor does not let the opponent off the hook. */
    val contempt: Int = 15,
) {
    /**
     * Safe mobility bonus by piece type and number of safe squares: grows with the square root,
     * zero around the usual mobility of the piece, negative for passive pieces.
     */
    val mobility: Array<Array<TaperedScore>> = arrayOf(
        arrayOf(TaperedScore.ZERO),
        mobilityTable(KNIGHT_INDEX, maxMoves = 8, usual = 4),
        mobilityTable(BISHOP_INDEX, maxMoves = 13, usual = 6),
        mobilityTable(ROOK_INDEX, maxMoves = 14, usual = 6),
        mobilityTable(QUEEN_INDEX, maxMoves = 27, usual = 10),
        arrayOf(TaperedScore.ZERO),
    )

    private fun mobilityTable(type: Int, maxMoves: Int, usual: Int) = Array(maxMoves + 1) { moves ->
        val factor = (sqrt(moves.toDouble()) - sqrt(usual.toDouble())) * mobilityPercent / 100
        TaperedScore((mobilityScale[type].middlegame * factor).toInt(), (mobilityScale[type].endgame * factor).toInt())
    }

    /** Value of each parameter, by name, in the order of [PARAMETERS]. */
    fun toParameters(): Map<String, Int> = PARAMETERS.associate { it.name to it.get(this) }

    /** Copy with some parameters changed. */
    fun with(parameters: Map<String, Int>): SpaceWeights = parameters.entries.fold(this) { weights, (name, value) ->
        val parameter = PARAMETERS_BY_NAME[name] ?: throw IllegalArgumentException("Unknown weight: $name")
        require(value in parameter.range) { "Weight $name must be between ${parameter.range.first} and ${parameter.range.last}" }
        parameter.set(weights, value)
    }

    /** Text of a weights file (`name = value` lines), readable with [parse]. */
    fun format(): String = buildString {
        appendLine("# Weights of the Chessy \"Constrictor\" evaluation (Style Space), in centipawns.")
        appendLine("# Tapered weights have a middlegame (mg) and an endgame (eg) value.")
        // A blank line around each group of weights (tapered weights and arrays)
        var group: String? = null
        for ((name, value) in toParameters()) {
            val prefix = if ('.' in name) name.substringBefore('.') else ""
            if (prefix != group && (prefix.isNotEmpty() || group != "")) appendLine()
            group = prefix
            appendLine("$name = $value")
        }
    }

    fun save(file: File) = file.writeText(format())

    /** A named integer of the weights, to save them in a file and tune them. */
    class Parameter(val name: String, val get: (SpaceWeights) -> Int, val set: (SpaceWeights, Int) -> SpaceWeights, val range: IntRange)

    companion object {
        private const val KNIGHT_INDEX = 1
        private const val BISHOP_INDEX = 2
        private const val ROOK_INDEX = 3
        private const val QUEEN_INDEX = 4

        private val PIECE_NAMES = listOf("pawn", "knight", "bishop", "rook", "queen", "king")
        private val PIECES = (0..4).associateBy { PIECE_NAMES[it] }
        private val MOBILE_PIECES = (1..4).associateBy { PIECE_NAMES[it] }
        private val TRAPPABLE_PIECES = listOf(1, 2, 4).associateBy { PIECE_NAMES[it] }
        /** Relative ranks 2 to 7 (the first and last ranks have no pawns). */
        private val PAWN_RANKS = (1..6).associateBy { "rank${it + 1}" }

        private fun scalar(name: String, get: (SpaceWeights) -> Int, range: IntRange = -10_000..10_000, set: SpaceWeights.(Int) -> SpaceWeights) =
            listOf(Parameter(name, get, { weights, value -> weights.set(value) }, range))

        /** Range of the bonuses (they cannot become penalties when tuned). */
        private val BONUS = 0..10_000
        /** Range of the penalties. */
        private val PENALTY = -10_000..0
        private val ANY = -10_000..10_000

        private fun tapered(name: String, get: (SpaceWeights) -> TaperedScore, range: IntRange = ANY, set: SpaceWeights.(TaperedScore) -> SpaceWeights) = listOf(
            Parameter("$name.mg", { get(it).middlegame }, { weights, value -> weights.set(get(weights).copy(middlegame = value)) }, range),
            Parameter("$name.eg", { get(it).endgame }, { weights, value -> weights.set(get(weights).copy(endgame = value)) }, range),
        )

        /** Tapered weights of an array, for the given names and indexes. */
        private fun taperedArray(
            name: String, indexes: Map<String, Int>, get: (SpaceWeights) -> Array<TaperedScore>, range: IntRange = ANY,
            set: SpaceWeights.(Array<TaperedScore>) -> SpaceWeights,
        ) = indexes.flatMap { (label, index) ->
            tapered("$name.$label", { get(it)[index] }, range) { score -> set(get(this).copyOf().also { it[index] = score }) }
        }

        /** All the weights that can be saved and tuned (all but the mobility tables, computed from [mobilityScale]). */
        val PARAMETERS: List<Parameter> = listOf(
            taperedArray("pieceValues", PIECES, { it.pieceValues }, BONUS) { copy(pieceValues = it) },
            scalar("piecePstPercent", { it.piecePstPercent }, 0..200) { copy(piecePstPercent = it) },
            scalar("mobilityPercent", { it.mobilityPercent }, 0..500) { copy(mobilityPercent = it) },
            taperedArray("mobilityScale", MOBILE_PIECES, { it.mobilityScale }, BONUS) { copy(mobilityScale = it) },
            scalar("trappedMobility", { it.trappedMobility }, 0..3) { copy(trappedMobility = it) },
            taperedArray("trapped", TRAPPABLE_PIECES, { it.trapped }, PENALTY) { copy(trapped = it) },
            scalar("worstPiecePercent", { it.worstPiecePercent }, 0..200) { copy(worstPiecePercent = it) },
            scalar("spacePercent", { it.spacePercent }, 0..500) { copy(spacePercent = it) },
            scalar("spaceMaterialThreshold", { it.spaceMaterialThreshold }, 0..8000) { copy(spaceMaterialThreshold = it) },
            tapered("territory", { it.territory }, BONUS) { copy(territory = it) },
            tapered("kingZoneControl", { it.kingZoneControl }, BONUS) { copy(kingZoneControl = it) },
            tapered("restriction", { it.restriction }, BONUS) { copy(restriction = it) },
            tapered("knightOutpost", { it.knightOutpost }, BONUS) { copy(knightOutpost = it) },
            tapered("bishopOutpost", { it.bishopOutpost }, BONUS) { copy(bishopOutpost = it) },
            tapered("rookOpenFile", { it.rookOpenFile }, BONUS) { copy(rookOpenFile = it) },
            tapered("rookSemiOpenFile", { it.rookSemiOpenFile }, BONUS) { copy(rookSemiOpenFile = it) },
            tapered("rookOnSeventh", { it.rookOnSeventh }, BONUS) { copy(rookOnSeventh = it) },
            tapered("doubledPawn", { it.doubledPawn }, PENALTY) { copy(doubledPawn = it) },
            tapered("isolatedPawn", { it.isolatedPawn }, PENALTY) { copy(isolatedPawn = it) },
            taperedArray("connectedPawn", PAWN_RANKS, { it.connectedPawn }, BONUS) { copy(connectedPawn = it) },
            taperedArray("passedPawn", PAWN_RANKS, { it.passedPawn }, BONUS) { copy(passedPawn = it) },
            scalar("passedEnemyKingDistance", { it.passedEnemyKingDistance }, 0..100) { copy(passedEnemyKingDistance = it) },
            scalar("passedOwnKingDistance", { it.passedOwnKingDistance }, 0..100) { copy(passedOwnKingDistance = it) },
            tapered("overextendedPawn", { it.overextendedPawn }, PENALTY) { copy(overextendedPawn = it) },
            tapered("hole", { it.hole }, PENALTY) { copy(hole = it) },
            tapered("threatByPawn", { it.threatByPawn }, BONUS) { copy(threatByPawn = it) },
            tapered("threatByMinor", { it.threatByMinor }, BONUS) { copy(threatByMinor = it) },
            tapered("threatByRook", { it.threatByRook }, BONUS) { copy(threatByRook = it) },
            tapered("hangingPiece", { it.hangingPiece }, BONUS) { copy(hangingPiece = it) },
            tapered("hangingPawn", { it.hangingPawn }, BONUS) { copy(hangingPawn = it) },
            scalar("kingDanger", { it.kingDanger }, 0..100) { copy(kingDanger = it) },
            scalar("kingDangerMinUnits", { it.kingDangerMinUnits }, 0..50) { copy(kingDangerMinUnits = it) },
            scalar("maxKingDanger", { it.maxKingDanger }, 0..2000) { copy(maxKingDanger = it) },
            tapered("pawnShield", { it.pawnShield }, BONUS) { copy(pawnShield = it) },
            tapered("openFileNearKing", { it.openFileNearKing }, PENALTY) { copy(openFileNearKing = it) },
            tapered("bishopPair", { it.bishopPair }, BONUS) { copy(bishopPair = it) },
            tapered("tempo", { it.tempo }, BONUS) { copy(tempo = it) },
            scalar("contempt", { it.contempt }, -200..200) { copy(contempt = it) },
        ).flatten()

        private val PARAMETERS_BY_NAME = PARAMETERS.associateBy { it.name }

        /**
         * Weights not tuned: the style (space, territory, restriction and mobility), the contempt (not part of the static
         * evaluation) and the value of a pawn in the middlegame (the unit of the scores).
         */
        val STYLE: Set<String> = setOf(
            "pieceValues.pawn.mg", "mobilityPercent", "spacePercent", "territory.mg", "territory.eg",
            "kingZoneControl.mg", "kingZoneControl.eg", "restriction.mg", "restriction.eg", "contempt",
        )

        /** Weights of a file (`name = value` lines, `#` comments) on top of [base]: missing weights keep their value. */
        fun parse(text: String, base: SpaceWeights = SpaceWeights()): SpaceWeights {
            val parameters = text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .associate { line ->
                    val (name, value) = line.split('=', limit = 2).map { it.trim() }.also {
                        require(it.size == 2) { "Invalid weight (expected name = value): $line" }
                    }
                    name to (value.toIntOrNull() ?: throw IllegalArgumentException("Invalid value of $name: $value"))
                }
            return base.with(parameters)
        }

        fun load(file: File): SpaceWeights = parse(file.readText())

        /** Tuned weights of the resource `space.weights`, or the starting values if it is missing. */
        val DEFAULT: SpaceWeights = SpaceWeights::class.java.getResource("space.weights")?.readText()?.let { parse(it) } ?: SpaceWeights()
    }
}
