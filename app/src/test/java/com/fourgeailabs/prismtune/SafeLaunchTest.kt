package com.fourgeailabs.prismtune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.0 safe launch: the saved-queue validation predicate used
 * before any restore — empty, out-of-range, or blank-key
 * windows must never reach the player on launch.
 */
fun isValidSavedQueue(trackKeys: List<String>, index: Int): Boolean =
    trackKeys.isNotEmpty() && index in trackKeys.indices && trackKeys.all { it.isNotBlank() }

class SafeLaunchTest {
    @Test fun validWindow_passes() {
        assertTrue(isValidSavedQueue(listOf("1", "2", "3"), 1))
    }

    @Test fun emptyWindow_fails() {
        assertFalse(isValidSavedQueue(emptyList(), 0))
    }

    @Test fun outOfRangeIndex_fails() {
        assertFalse(isValidSavedQueue(listOf("1", "2"), 5))
        assertFalse(isValidSavedQueue(listOf("1", "2"), -1))
    }

    @Test fun blankKey_fails() {
        assertFalse(isValidSavedQueue(listOf("1", "", "3"), 0))
    }
}
