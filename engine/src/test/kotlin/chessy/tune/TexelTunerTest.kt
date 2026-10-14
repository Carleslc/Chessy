package chessy.tune

import chessy.eval.SpaceWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TexelTunerTest {

    // White is a knight up and wins; black is a knight up and wins; a balanced position is a draw
    private val positions = listOf(
        TexelTuner.TrainingPosition("4k3/pppppppp/8/8/8/2N5/PPPPPPPP/4K3 w - - 0 1", 1.0),
        TexelTuner.TrainingPosition("4k3/pppppppp/2n5/8/8/8/PPPPPPPP/4K3 b - - 0 1", 0.0),
        TexelTuner.TrainingPosition("4k3/pppppppp/8/8/8/8/PPPPPPPP/4K3 w - - 0 1", 0.5),
    )

    @Test
    fun sigmoid() {
        assertEquals(0.5, TexelTuner.sigmoid(0, 1.0))
        assertEquals(10.0 / 11.0, TexelTuner.sigmoid(400, 1.0), 1e-9)
        assertEquals(1.0 / 11.0, TexelTuner.sigmoid(-400, 1.0), 1e-9)
    }

    @Test
    fun tuningReducesTheError() {
        val tuner = TexelTuner(positions, threads = 2)
        try {
            // A cheap knight predicts draws: the tuner must give it more value
            val start = SpaceWeights.DEFAULT.with(mapOf("pieceValues.knight.mg" to 100, "pieceValues.knight.eg" to 100))
            val k = 1.0
            val tuned = tuner.tune(start, listOf("pieceValues.knight.mg", "pieceValues.knight.eg"), k, maxPasses = 20, log = {})
            assertTrue(tuner.error(tuned, k) < tuner.error(start, k))
            // With a single knight the phase is almost the endgame, where the value matters
            assertTrue(tuned.pieceValues[1].endgame > 300)
        } finally {
            tuner.close()
        }
    }

    @Test
    fun maxChangeLimitsTheWeights() {
        val tuner = TexelTuner(positions, threads = 2)
        try {
            val start = SpaceWeights.DEFAULT.with(mapOf("pieceValues.knight.mg" to 100, "pieceValues.knight.eg" to 100))
            val names = listOf("pieceValues.knight.mg", "pieceValues.knight.eg")
            val tuned = tuner.tune(start, names, 1.0, maxPasses = 20, maxChange = 50, log = {})
            assertTrue(tuned.pieceValues[1].endgame in 101..200, "${tuned.pieceValues[1]}")
        } finally {
            tuner.close()
        }
    }

    @Test
    fun readTrainingFile() {
        val file = kotlin.io.path.createTempFile(suffix = ".txt").toFile()
        try {
            file.writeText("# fen;result\n${positions[0].fen};1\n\n${positions[2].fen};0.5\n")
            val read = TexelTuner.read(file)
            assertEquals(listOf(1.0, 0.5), read.map { it.result })
            assertEquals(positions[2].fen, read[1].fen)
        } finally {
            file.delete()
        }
    }
}
