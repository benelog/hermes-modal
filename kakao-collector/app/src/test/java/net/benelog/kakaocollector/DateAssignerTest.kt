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

    // 최상단 마커가 인라인 구분선(그 날의 시작)인 프레임: 그 '위' 메시지는 이전 날짜이므로
    // 구분선 날짜를 내려받으면 안 된다 → 미상("").
    @Test fun aboveTopmostMarkerIsUnknown() {
        val markers = listOf(DateAssigner.Marker(top = 600, date = "2026-07-05"))
        assertEquals(listOf("", "2026-07-05"), DateAssigner.assign(markers, listOf(200, 800)))
    }

    // 2026-07-05 실측 오수집 재현: 스크롤 중 스티키 뱃지가 이전 화면 날짜(7/5)로 지연된 채
    // 7/4 메시지 위에 떠 있고 아래에 7/5 구분선이 보이는 프레임 — 구분선 위 메시지가 구분선
    // 날짜 이상을 받는 모순 → 미상 처리(중복 행 생성 차단). 구분선 아래는 정상 부여.
    @Test fun staleStickyContradictingInlineSeparatorIsBlanked() {
        val markers = listOf(
            DateAssigner.Marker(top = 0, date = "2026-07-05"),
            DateAssigner.Marker(top = 900, date = "2026-07-05"),
        )
        assertEquals(listOf("", "2026-07-05"), DateAssigner.assign(markers, listOf(300, 1000)))
    }
}
