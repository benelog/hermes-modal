package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownValueTest {
    @Test fun earliestPrefersKnownThenSmaller() {
        assertEquals("09:05", KnownValue.earliest("09:05", "14:20"))
        assertEquals("09:05", KnownValue.earliest("14:20", "09:05"))
        assertEquals("2026-07-09", KnownValue.earliest("", "2026-07-09"))
        assertEquals("2026-07-09", KnownValue.earliest("2026-07-09", ""))
        assertEquals("", KnownValue.earliest("", ""))
    }

    @Test fun compatibleOnlyConflictsBetweenKnownDifferentValues() {
        assertTrue(KnownValue.compatible("2026-07-09", "2026-07-09"))
        assertTrue(KnownValue.compatible("", "2026-07-09"))
        assertTrue(KnownValue.compatible("2026-07-09", ""))
        assertFalse(KnownValue.compatible("2026-07-09", "2026-07-10"))
    }
}
