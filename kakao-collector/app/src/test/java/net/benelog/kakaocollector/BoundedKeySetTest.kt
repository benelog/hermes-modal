package net.benelog.kakaocollector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedKeySetTest {
    @Test fun addReturnsTrueOnlyForNewKeys() {
        val set = BoundedKeySet(cap = 10)
        assertTrue(set.add("a"))
        assertFalse(set.add("a"))
        assertTrue("a" in set)
    }

    @Test fun oldestKeyIsEvictedOverCap() {
        val set = BoundedKeySet(cap = 2)
        set.add("a")
        set.add("b")
        set.add("c") // "a" 축출
        assertFalse("a" in set)
        assertTrue("b" in set)
        assertTrue("c" in set)
    }

    @Test fun addAllSeedsRespectingCap() {
        val set = BoundedKeySet(cap = 2)
        set.addAll(listOf("a", "b", "c"))
        assertFalse("a" in set)
        assertTrue("b" in set)
        assertTrue("c" in set)
    }
}
