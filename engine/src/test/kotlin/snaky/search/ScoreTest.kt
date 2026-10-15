package snaky.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreTest {

    @Test
    fun uciScores() {
        assertEquals("cp 35", Score.toUci(35))
        assertEquals("cp -120", Score.toUci(-120))
        assertEquals("mate 1", Score.toUci(Score.mateIn(1)))
        assertEquals("mate 2", Score.toUci(Score.mateIn(3)))
        assertEquals("mate 2", Score.toUci(Score.mateIn(4)))
        assertEquals("mate -1", Score.toUci(Score.matedIn(2)))
        assertEquals("mate 0", Score.toUci(Score.matedIn(0)))
    }

    @Test
    fun mates() {
        assertTrue(Score.isMate(Score.mateIn(10)))
        assertTrue(Score.isMate(Score.matedIn(10)))
        assertFalse(Score.isMate(900))
        assertTrue(Score.mateIn(1) > Score.mateIn(3))
    }
}
