package snaky.eval

import snaky.core.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpaceWeightsTest {

    @Test
    fun formatAndParse() {
        val weights = SpaceWeights().with(mapOf("territory.mg" to 7, "passedPawn.rank5.eg" to 60, "kingDanger" to 9))
        val parsed = SpaceWeights.parse(weights.format())
        assertEquals(weights.toParameters(), parsed.toParameters())
        assertEquals(TaperedScore(7, 2), parsed.territory)
        assertEquals(60, parsed.passedPawn[4].endgame)
    }

    @Test
    fun missingWeightsKeepTheirValue() {
        val weights = SpaceWeights.parse("# Only one weight\ntempo.mg = 20\n")
        assertEquals(TaperedScore(20, SpaceWeights().tempo.endgame), weights.tempo)
        assertEquals(SpaceWeights().toParameters() - "tempo.mg", weights.toParameters() - "tempo.mg")
    }

    @Test
    fun invalidWeights() {
        assertFailsWith<IllegalArgumentException> { SpaceWeights.parse("unknown = 1") }
        assertFailsWith<IllegalArgumentException> { SpaceWeights.parse("tempo.mg = high") }
        assertFailsWith<IllegalArgumentException> { SpaceWeights.parse("tempo.mg") }
        assertFailsWith<IllegalArgumentException> { SpaceWeights.parse("trappedMobility = 10") }
    }

    @Test
    fun weightsChangeTheEvaluation() {
        val position = Position.fromFen("4k3/pppppppp/8/8/8/2N5/PPPPPPPP/4K3 w - - 0 1")
        val more = SpaceEvaluator(SpaceWeights.DEFAULT.with(mapOf("pieceValues.knight.mg" to 600, "pieceValues.knight.eg" to 600)))
        assertNotEquals(SpaceEvaluator().evaluate(position), more.evaluate(position))
    }

    @Test
    fun mobilityTablesFollowTheScale() {
        val weights = SpaceWeights().with(mapOf("mobilityScale.knight.mg" to 60))
        assertEquals(SpaceWeights().mobility[1][8].middlegame * 2, weights.mobility[1][8].middlegame, absoluteTolerance = 1)
    }

    @Test
    fun fullStyle() {
        val full = Evaluator.named("full")
        assertEquals("Full", full.name)
        assertTrue((full as SpaceEvaluator).weights === SpaceWeights.FULL)
        assertNotEquals(SpaceWeights.DEFAULT.toParameters(), SpaceWeights.FULL.toParameters())
        // Other weights keep the name of the style
        val custom = Evaluator.named("Full", SpaceWeights.DEFAULT) as SpaceEvaluator
        assertEquals("Full", custom.name)
        assertTrue(custom.weights === SpaceWeights.DEFAULT)
        assertTrue(Evaluator.named("Material", SpaceWeights.DEFAULT) is MaterialEvaluator)
    }

    @Test
    fun styleWeightsExist() {
        val names = SpaceWeights.PARAMETERS.map { it.name }.toSet()
        for (name in SpaceWeights.STYLE) assert(name in names) { name }
    }

    private fun assertEquals(expected: Int, actual: Int, absoluteTolerance: Int) =
        kotlin.test.assertTrue(Math.abs(expected - actual) <= absoluteTolerance, "expected $expected, actual $actual")
}
