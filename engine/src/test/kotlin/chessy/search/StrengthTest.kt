package chessy.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrengthTest {

    @Test
    fun ladder() {
        // From the greedy search to Full, in steps of 100
        assertEquals(Strength.Setting("Greedy", greedyDepth = 2), Strength.settingFor(900))
        assertEquals(Strength.Setting("Greedy", greedyDepth = 3), Strength.settingFor(1100))
        assertEquals(Strength.Setting("Greedy", greedyDepth = 4), Strength.settingFor(1200))
        assertEquals("Space", Strength.settingFor(1300).style)
        assertEquals(Strength.Setting("Full"), Strength.settingFor(2800))
        assertEquals(Strength.Setting("Full"), Strength.settingFor(5000))
        assertEquals(Strength.Setting("Greedy", greedyDepth = 2), Strength.settingFor(100))
    }

    @Test
    fun moreEloMoreNodes() {
        var previous = 0L
        for (elo in 1300..2700 step 100) {
            val nodes = Strength.settingFor(elo).nodes!!
            assertTrue(nodes > previous, "$elo: $nodes")
            previous = nodes
            // The estimate of the setting is the chosen Elo (within rounding)
            assertTrue(Math.abs(Strength.estimatedElo(Strength.settingFor(elo)) - elo) <= 50, "$elo")
        }
        // Each doubling of the nodes is worth about 135 Elo
        assertEquals(4096L, Strength.nodesFor(1976))
    }

    @Test
    fun otherStyles() {
        // The style is kept for the levels it can reach by limiting its nodes
        assertEquals("Material", Strength.settingFor(2000, "Material").style)
        assertNull(Strength.settingFor(2600, "Material").nodes)
        assertEquals(Strength.Setting("Full"), Strength.settingFor(2800, "Material"))
    }

    @Test
    fun threads() {
        // 4 threads add about 100 Elo to the search without limit, but not to the limited levels (played with one thread)
        assertEquals(2850, Strength.estimatedElo(Strength.Setting("Space"), threads = 4))
        assertEquals(2850, Strength.estimatedElo(Strength.Setting("Space"), threads = 16))
        assertEquals(Strength.estimatedElo(Strength.settingFor(2000)), Strength.estimatedElo(Strength.settingFor(2000), threads = 4))
    }

    @Test
    fun rounding() {
        assertEquals(1500, Strength.roundElo(1549))
        assertEquals(1600, Strength.roundElo(1550))
        assertEquals(Strength.MIN_ELO, Strength.roundElo(0))
        assertEquals(Strength.MAX_ELO, Strength.roundElo(9999))
    }
}
