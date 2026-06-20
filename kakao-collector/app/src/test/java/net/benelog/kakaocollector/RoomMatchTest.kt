package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMatchTest {
    @Test fun parseSplitsTrimsAndDropsBlankLines() {
        assertEquals(listOf("A", "B"), RoomMatch.parse(" A \n\n  B\n"))
    }

    @Test fun matchReturnsTargetContainedInTitle() {
        // 제목엔 부가정보가 붙을 수 있으니 contains 로 매칭, 반환은 '대상(정규형)'.
        assertEquals("ABC(북클럽)", RoomMatch.match("ABC(북클럽)", listOf("X", "ABC(북클럽)")))
    }

    @Test fun matchReturnsNullWhenNoTargetMatches() {
        assertNull(RoomMatch.match("다른방", listOf("ABC(북클럽)")))
    }

    @Test fun matchIgnoresBlankTargets() {
        assertNull(RoomMatch.match("anything", listOf("")))
    }
}
