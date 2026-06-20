package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DedupeKeyTest {
    @Test fun sameFieldsProduceSameKey() {
        assertEquals(
            DedupeKey.of("r", "s", "t", "12:00"),
            DedupeKey.of("r", "s", "t", "12:00"),
        )
    }

    @Test fun differentRoomProducesDifferentKey() {
        assertNotEquals(
            DedupeKey.of("roomA", "s", "t", ""),
            DedupeKey.of("roomB", "s", "t", ""),
        )
    }

    @Test fun fieldsAreSeparatedSoConcatenationCannotCollide() {
        // "ab"+""  vs  "a"+"b" 가 구분자 없이는 충돌. 구분자(U+0001)로 분리되어야 함.
        assertNotEquals(
            DedupeKey.of("ab", "", "t", ""),
            DedupeKey.of("a", "b", "t", ""),
        )
    }
}
