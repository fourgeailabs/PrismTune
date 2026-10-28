package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.playback.upNextOrderFrom
import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleUpNextTest {
    @Test fun naturalOrder_withoutShuffle() {
        assertEquals(listOf(3, 4), upNextOrderFrom(2, 5, false, { -1 }))
    }

    @Test fun naturalOrder_atEnd_isEmpty() {
        assertEquals(emptyList<Int>(), upNextOrderFrom(4, 5, false, { -1 }))
    }

    @Test fun shuffledOrder_followsPlayerWalkNotNaturalOrder() {
        // Player's shuffled sequence after song 2 is 4, 1, 3, 0.
        val next = mapOf(2 to 4, 4 to 1, 1 to 3, 3 to 0, 0 to 2)
        assertEquals(listOf(4, 1, 3, 0), upNextOrderFrom(2, 5, true, { next[it] ?: -1 }))
    }

    @Test fun shuffledOrder_atShuffledEnd_isEmpty() {
        assertEquals(emptyList<Int>(), upNextOrderFrom(1, 5, true, { -1 }))
    }

    @Test fun shuffledOrder_stopsBeforeRepeating() {
        // Repeat-all would cycle 2 -> 4 -> 2; Up Next shows 4 once.
        val next = mapOf(2 to 4, 4 to 2)
        assertEquals(listOf(4), upNextOrderFrom(2, 5, true, { next[it] ?: -1 }))
    }

    @Test fun limit_isRespected() {
        val next = mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)
        assertEquals(listOf(1, 2), upNextOrderFrom(0, 5, true, { next[it] ?: -1 }, limit = 2))
    }

    @Test fun invalidCurrent_isEmpty() {
        assertEquals(emptyList<Int>(), upNextOrderFrom(9, 5, true, { -1 }))
        assertEquals(emptyList<Int>(), upNextOrderFrom(-1, 5, false, { -1 }))
    }
}
