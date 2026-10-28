package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.toggleDislike
import com.fourgeailabs.prismtune.data.toggleLike
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbToggleTest {
    @Test fun secondLikeTapUnselectsBackToNeutral() {
        val (liked, disliked) = toggleLike(setOf("k"), emptySet(), "k")
        assertEquals(emptySet<String>(), liked)
        assertEquals(emptySet<String>(), disliked)
    }

    @Test fun firstLikeTapLikesAndClearsDislike() {
        val (liked, disliked) = toggleLike(emptySet(), setOf("k"), "k")
        assertEquals(setOf("k"), liked)
        assertEquals(emptySet<String>(), disliked)
    }

    @Test fun secondDislikeTapUnselectsBackToNeutral() {
        val (liked, disliked) = toggleDislike(emptySet(), setOf("k"), "k")
        assertEquals(emptySet<String>(), liked)
        assertEquals(emptySet<String>(), disliked)
    }

    @Test fun firstDislikeTapDislikesAndClearsLike() {
        val (liked, disliked) = toggleDislike(setOf("k"), emptySet(), "k")
        assertEquals(emptySet<String>(), liked)
        assertEquals(setOf("k"), disliked)
    }

    @Test fun togglesLeaveOtherSongsAlone() {
        val (liked, disliked) = toggleLike(setOf("a", "k"), setOf("b"), "k")
        assertEquals(setOf("a"), liked)
        assertEquals(setOf("b"), disliked)
    }
}
