package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillPlannerTest {
    // SEEK 종료: from '이전 날'이 보여야 from 당일 시작 구분선까지 확보된 것.
    @Test fun seekDoneOnlyBeforeFromDate() {
        assertTrue(BackfillPlanner.seekDone("2026-07-09", "2026-07-10"))
        assertFalse(BackfillPlanner.seekDone("2026-07-10", "2026-07-10")) // 당일은 아직
        assertFalse(BackfillPlanner.seekDone("2026-07-11", "2026-07-10"))
        assertFalse(BackfillPlanner.seekDone("", "2026-07-10")) // 날짜 미상이면 계속
    }

    // COLLECT 종료: 화면의 가장 과거 날짜조차 to 다음 날이면 범위를 전부 지나온 것.
    @Test fun collectDoneOnlyAfterToDate() {
        assertTrue(BackfillPlanner.collectDone("2026-07-13", "2026-07-12"))
        assertFalse(BackfillPlanner.collectDone("2026-07-12", "2026-07-12")) // 당일 포함
        assertFalse(BackfillPlanner.collectDone("", "2026-07-12")) // 미상이면 계속
    }

    @Test fun minDateHandlesBlanks() {
        assertEquals("2026-07-09", BackfillPlanner.minDate("2026-07-09", "2026-07-10"))
        assertEquals("2026-07-09", BackfillPlanner.minDate("", "2026-07-09"))
        assertEquals("2026-07-09", BackfillPlanner.minDate("2026-07-09", ""))
        assertEquals("", BackfillPlanner.minDate("", ""))
    }
}
