package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DedupeKeyTest {
    @Test fun sameFieldsProduceSameKey() {
        assertEquals(
            DedupeKey.of("r", "t", "2026-06-24"),
            DedupeKey.of("r", "t", "2026-06-24"),
        )
    }

    // 보낸이 추정이 스크랩마다 흔들려도 같은 (방,본문,날짜)면 같은 키 — 오귀속 재수집이
    // 새 행을 만들지 못하게 한다. (sender 자체가 of() 시그니처에서 빠졌음을 문서화)
    @Test fun keyDependsOnlyOnRoomTextDate() {
        assertEquals(
            DedupeKey.of("아카라카북클럽", "안녕", "2026-06-24"),
            DedupeKey.of("아카라카북클럽", "안녕", "2026-06-24"),
        )
    }

    @Test fun differentRoomProducesDifferentKey() {
        assertNotEquals(
            DedupeKey.of("roomA", "t", ""),
            DedupeKey.of("roomB", "t", ""),
        )
    }

    @Test fun differentDayProducesDifferentKey() {
        assertNotEquals(
            DedupeKey.of("r", "t", "2026-06-24"),
            DedupeKey.of("r", "t", "2026-06-25"),
        )
    }

    @Test fun fieldsAreSeparatedSoConcatenationCannotCollide() {
        // 구분자(U+0001) 없으면 "ab"+"t" 와 "a"+"bt" 가 충돌. 분리되어야 함.
        assertNotEquals(
            DedupeKey.of("ab", "t", ""),
            DedupeKey.of("a", "bt", ""),
        )
    }
}
