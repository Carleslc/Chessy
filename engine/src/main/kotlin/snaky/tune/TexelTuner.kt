package snaky.tune

import snaky.core.Position
import snaky.core.WHITE
import snaky.eval.SpaceEvaluator
import snaky.eval.SpaceWeights
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Texel's tuning method (https://www.chessprogramming.org/Texel%27s_Tuning_Method) for the weights of the Space evaluation.
 *
 * The evaluation of a position predicts the result of the game: the expected score of white is
 * `sigmoid(eval) = 1 / (1 + 10^(-k * eval / 400))`. The tuner minimizes the mean squared error between the expected
 * score and the real result of the game of each position, changing one weight at a time (local search):
 * each weight is moved up or down by its step while the error decreases, and the steps are halved when no weight improves.
 *
 * Positions should be quiet (no captures pending, not in check), because the static evaluation does not see tactics.
 */
class TexelTuner(private val positions: List<TrainingPosition>, private val threads: Int = Runtime.getRuntime().availableProcessors()) {

    /** A position and the result of its game for white: 1 (win), 0.5 (draw) or 0 (loss). */
    class TrainingPosition(val fen: String, val result: Double)

    private val executor = Executors.newFixedThreadPool(threads) { runnable -> Thread(runnable, "tuner").apply { isDaemon = true } }
    private val chunks = positions.chunked((positions.size + threads - 1) / threads)

    /** Mean squared error of the predictions of [weights] with the scaling constant [k]. */
    fun error(weights: SpaceWeights, k: Double): Double {
        val tasks = chunks.map { chunk ->
            Callable {
                val evaluator = SpaceEvaluator(weights)
                val position = Position.startingPosition()
                var sum = 0.0
                for (training in chunk) {
                    position.setFen(training.fen)
                    val score = evaluator.evaluate(position).let { if (position.sideToMove == WHITE) it else -it }
                    val difference = training.result - sigmoid(score, k)
                    sum += difference * difference
                }
                sum
            }
        }
        return executor.invokeAll(tasks).sumOf { it.get() } / positions.size
    }

    /** The scaling constant that minimizes the error of [weights] (ternary search, the error is convex in k). */
    fun fitK(weights: SpaceWeights, low: Double = 0.1, high: Double = 3.0): Double {
        var a = low
        var b = high
        while (b - a > 0.001) {
            val m1 = a + (b - a) / 3
            val m2 = b - (b - a) / 3
            if (error(weights, m1) < error(weights, m2)) b = m2 else a = m1
        }
        return (a + b) / 2
    }

    /**
     * Tunes the weights named [names] starting from [start], until no weight improves with steps of 1
     * or after [maxPasses] passes. [onPass] is called after every pass with the best weights so far.
     *
     * With [maxChange], each weight stays within its start value ± max([maxChange], |start value|): rare terms
     * (seen in few positions) cannot take extreme values that only fit the noise of the training games.
     */
    fun tune(
        start: SpaceWeights, names: List<String>, k: Double, maxPasses: Int = 100, maxChange: Int? = null,
        log: (String) -> Unit = ::println, onPass: (SpaceWeights) -> Unit = {},
    ): SpaceWeights {
        val parameters = SpaceWeights.PARAMETERS.filter { it.name in names }
        val ranges = parameters.associate { parameter ->
            val value = parameter.get(start)
            val limit = if (maxChange == null) Int.MAX_VALUE / 2 else max(maxChange, abs(value))
            parameter.name to maxOf(parameter.range.first, value - limit)..minOf(parameter.range.last, value + limit)
        }
        var best = start
        var bestError = error(best, k)
        log("Start: error %.7f, k %.3f, %d weights, %d positions".format(Locale.ROOT, bestError, k, parameters.size, positions.size))

        // Initial steps: about a tenth of each value, so big values (material) move faster
        val steps = parameters.associate { it.name to max(1, abs(it.get(start)) / 10).coerceAtMost(32) }.toMutableMap()

        for (pass in 1..maxPasses) {
            var improved = 0
            for (parameter in parameters) {
                val step = steps.getValue(parameter.name)
                var changed = false
                for (direction in intArrayOf(1, -1)) {
                    // Keep moving in the same direction while the error decreases
                    while (true) {
                        val value = parameter.get(best) + direction * step
                        if (value !in ranges.getValue(parameter.name)) break
                        val candidate = parameter.set(best, value)
                        val candidateError = error(candidate, k)
                        if (candidateError >= bestError) break
                        best = candidate
                        bestError = candidateError
                        changed = true
                    }
                    if (changed) break
                }
                if (changed) improved++ else steps[parameter.name] = max(1, step / 2)
            }
            log("Pass %d: error %.7f, %d weights improved".format(Locale.ROOT, pass, bestError, improved))
            onPass(best)
            if (improved == 0 && steps.values.all { it == 1 }) break
        }
        return best
    }

    fun close() = executor.shutdown()

    companion object {
        fun sigmoid(score: Int, k: Double): Double = 1.0 / (1.0 + 10.0.pow(-k * score / 400.0))

        /**
         * Reads a training file: one position per line, `<fen>;<result>` with the result for white (1, 0.5 or 0).
         * Lines starting with `#` are ignored.
         */
        fun read(file: File, limit: Int = Int.MAX_VALUE): List<TrainingPosition> = file.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .take(limit)
                .map { line ->
                    val (fen, result) = line.split(';', limit = 2)
                    TrainingPosition(fen.trim(), result.trim().toDouble())
                }
                .toList()
        }
    }
}
