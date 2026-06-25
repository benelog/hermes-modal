package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Test

class DateAssignerTest {
    // 단일 날짜 화면: 마커 1개(상단 뱃지) → 모든 메시지 동일 날짜
    @Test fun singleMarkerAppliesToAll() {
        val markers = listOf(DateAssigner.Marker(top = 100, date = "2026-06-24"))
        val msgTops = listOf(300, 500, 800)
        assertEquals(
            listOf("2026-06-24", "2026-06-24", "2026-06-24"),
            DateAssigner.assign(markers, msgTops),
        )
    }

    // 경계 화면: 상단 뱃지(6/23) + 인라인 구분선(6/24) → 구분선 위는 6/23, 아래는 6/24
    @Test fun inlineSeparatorSplitsDays() {
        val markers = listOf(
            DateAssigner.Marker(top = 50, date = "2026-06-23"),
            DateAssigner.Marker(top = 600, date = "2026-06-24"),
        )
        val msgTops = listOf(200, 400, 700, 900)
        assertEquals(
            listOf("2026-06-23", "2026-06-23", "2026-06-24", "2026-06-24"),
            DateAssigner.assign(markers, msgTops),
        )
    }

    @Test fun noMarkersYieldBlank() =
        assertEquals(listOf("", ""), DateAssigner.assign(emptyList(), listOf(100, 200)))
}
