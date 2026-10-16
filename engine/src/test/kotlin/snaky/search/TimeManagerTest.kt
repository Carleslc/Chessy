package snaky.search

import snaky.core.BLACK
import snaky.core.WHITE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeManagerTest {

    @Test
    fun moveTime() {
        val time = TimeManager(SearchLimits(moveTime = 1000, moveOverhead = 100), WHITE)
        assertEquals(900, time.softLimit)
        assertEquals(900, time.hardLimit)
    }

    @Test
    fun clock() {
        val time = TimeManager(SearchLimits(whiteTime = 60_000, blackTime = 1000, whiteIncrement = 1000, moveOverhead = 100), WHITE)
        // 59900 / 30 + 750
        assertEquals(2746, time.softLimit)
        assertEquals(2746 * 4, time.hardLimit)
        assertTrue(time.canStartIteration(1000))
        assertFalse(time.canStartIteration(2000))
        assertTrue(time.mustAbort(2746 * 4))
    }

    @Test
    fun usesTheClockOfTheSideToMove() {
        val time = TimeManager(SearchLimits(whiteTime = 60_000, blackTime = 3000, moveOverhead = 0), BLACK)
        assertEquals(100, time.softLimit)
    }

    @Test
    fun neverMoreThanTheTimeLeft() {
        val time = TimeManager(SearchLimits(whiteTime = 500, movesToGo = 1, moveOverhead = 100), WHITE)
        assertTrue(time.hardLimit!! < 400)
        val almostNoTime = TimeManager(SearchLimits(whiteTime = 50, moveOverhead = 100), WHITE)
        assertEquals(1, almostNoTime.softLimit)
    }

    @Test
    fun ponderStartsTheClockAtPonderhit() {
        val limits = SearchLimits(whiteTime = 10_000, moveOverhead = 0, ponder = true)
        val time = TimeManager(limits, WHITE, searchStart = 0)
        val hard = time.hardLimit!!
        // While pondering there is no limit
        assertTrue(time.canStartIteration(1_000_000))
        assertFalse(time.mustAbort(1_000_000))
        // After the ponderhit the time counts from it
        limits.ponderhit.signal()
        val ponderhitElapsed = limits.ponderhit.time
        assertFalse(time.mustAbort(ponderhitElapsed + hard - 1))
        assertTrue(time.mustAbort(ponderhitElapsed + hard))
    }

    @Test
    fun noLimit() {
        assertNull(TimeManager(SearchLimits(infinite = true, whiteTime = 1000), WHITE).hardLimit)
        assertNull(TimeManager(SearchLimits(depth = 5), WHITE).hardLimit)
        assertTrue(TimeManager(SearchLimits(depth = 5), WHITE).canStartIteration(1_000_000))
    }
}
