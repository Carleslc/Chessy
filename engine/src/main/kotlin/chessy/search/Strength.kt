package chessy.search

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Playing strength (https://chessprogramming.org/Playing_Strength): the estimated Elo of each style, and how to play
 * at a chosen Elo (UCI options `UCI_LimitStrength` and `UCI_Elo`, `--elo` in the command line).
 *
 * Elo are in the scale of the `UCI_Elo` of Stockfish (anchored to CCRL Blitz), measured with games at 10+0.1 against
 * Stockfish with limited strength (see the README). From weakest to strongest, a chosen Elo plays:
 *
 * - the greedy search (only material, no quiescence) at the depth closest to the Elo, for the lowest levels;
 * - the alpha-beta search with its style (Space by default) limited to a number of nodes per move: it keeps every
 *   technique of the search (quiescence, extensions, reductions...) and only sees less. The Elo grows with the logarithm
 *   of the nodes (each doubling adds [ELO_PER_DOUBLING]);
 * - the Full style without limit, at the top.
 */
object Strength {

    const val STEP = 100

    /** Estimated Elo of the greedy search by depth (without quiescence, depth 4 is barely better than depth 3). */
    val GREEDY_ELO: Map<Int, Int> = mapOf(2 to 900, 3 to 1100, 4 to 1120)

    /** Estimated Elo of the styles of the alpha-beta search without limit. */
    val STYLE_ELO: Map<String, Int> = mapOf("Space" to 2750, "Full" to 2800, "Material" to 2550)

    /**
     * Elo of the Space style limited to [REFERENCE_NODES] nodes per move: a linear fit of the Elo against the logarithm
     * of the nodes, from games against Stockfish with 64 to 262144 nodes per move (README).
     */
    private const val REFERENCE_ELO = 1976.0
    private const val REFERENCE_NODES = 4096.0
    /** Elo gained by each doubling of the nodes per move. */
    private const val ELO_PER_DOUBLING = 135.0

    val MIN_ELO: Int = GREEDY_ELO.values.min()
    val MAX_ELO: Int = STYLE_ELO.getValue("Full")

    /** How to play: [style] (Greedy or an evaluator) with [greedyDepth] for Greedy, or limited to [nodes] per move. */
    data class Setting(val style: String, val greedyDepth: Int = 2, val nodes: Long? = null) {
        val isGreedy: Boolean get() = style == GREEDY

        /** Such as "Space", "Greedy, depth 2" or "Space, 2048 nodes per move". */
        override fun toString(): String = when {
            isGreedy -> "$style, depth $greedyDepth"
            nodes != null -> "$style, $nodes nodes per move"
            else -> style
        }
    }

    /**
     * Setting to play at [elo] (rounded to [STEP], within [MIN_ELO] and [MAX_ELO]), with the alpha-beta [style] for the
     * levels between the greedy search and its own Elo. The maximum plays Full.
     */
    fun settingFor(elo: Int, style: String = "Space"): Setting {
        val target = roundElo(elo)
        if (target >= MAX_ELO) return Setting("Full")
        val styleElo = STYLE_ELO[style] ?: STYLE_ELO.getValue("Space")
        if (target >= styleElo) return Setting(style)
        // The weakest alpha-beta search (MIN_NODES) is about 1300: below, the greedy search at the closest depth
        if (target < MIN_SEARCH_ELO) return Setting(GREEDY, greedyDepth = GREEDY_ELO.entries.minBy { Math.abs(it.value - target) }.key)
        return Setting(style, nodes = maxOf(nodesFor(target), MIN_NODES))
    }

    /** Estimated Elo of a [setting]. */
    fun estimatedElo(setting: Setting): Int = when {
        setting.isGreedy -> GREEDY_ELO[setting.greedyDepth]
            ?: if (setting.greedyDepth < 2) GREEDY_ELO.getValue(2) - 300 else GREEDY_ELO.getValue(4) + 100 * (setting.greedyDepth - 4)
        setting.nodes != null -> minOf(eloFor(setting.nodes), STYLE_ELO[setting.style] ?: STYLE_ELO.getValue("Space"))
        else -> STYLE_ELO[setting.style] ?: STYLE_ELO.getValue("Space")
    }

    fun roundElo(elo: Int): Int = ((elo.toDouble() / STEP).roundToInt() * STEP).coerceIn(MIN_ELO, MAX_ELO)

    /** Nodes per move of the alpha-beta search to play at [elo]. */
    fun nodesFor(elo: Int): Long = (REFERENCE_NODES * Math.pow(2.0, (elo - REFERENCE_ELO) / ELO_PER_DOUBLING)).roundToLong()

    private fun eloFor(nodes: Long): Int = (REFERENCE_ELO + ELO_PER_DOUBLING * ln(nodes / REFERENCE_NODES) / ln(2.0)).roundToInt()

    /** The alpha-beta search with fewer nodes is not weaker: it plays its first iterations, which are always complete. */
    private const val MIN_NODES = 64L
    /** Elo of the alpha-beta search with [MIN_NODES] (measured: about 1310). */
    private const val MIN_SEARCH_ELO = 1300

    const val GREEDY = "Greedy"
}
