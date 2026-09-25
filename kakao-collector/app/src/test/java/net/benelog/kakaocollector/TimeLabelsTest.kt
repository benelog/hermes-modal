package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeLabelsTest {
    // 남 말풍선(좌측) — 시각 라벨은 말풍선 오른쪽 아래(Pixel 10 Pro XL 실측 배치).
    private val bubble = FrameAssembler.Bubble("울 나라 숏크랙선수 풀과 비교불가", left = 210, right = 1071, top = 2418, bottom = 2610)

    private fun line(text: String, left: Int, top: Int, right: Int = left + 170, bottom: Int = top + 45) =
        TimeLabels.Line(text, left, top, right, bottom)

    @Test fun readsLabelBesideBubble() {
        val markers = TimeLabels.markers(listOf(line("오후 9:32", left = 1128, top = 2560)), listOf(bubble))
        assertEquals(listOf(TimeAssigner.Marker(top = 2560, time = "21:32")), markers)
    }

    // 본문 속 시각 표현은 말풍선 영역 안이므로 라벨이 아니다.
    @Test fun ignoresTimeInsideBubbleText() {
        assertEquals(emptyList<TimeAssigner.Marker>(), TimeLabels.markers(listOf(line("내일 오후 3:00에 만나요", 240, 2450)), listOf(bubble)))
    }

    // 오전/오후 없이 읽힌 숫자는 12시간제 판별이 안 돼 버리고, 안 읽음 수("58")도 무시.
    @Test fun requiresMeridiemPrefix() {
        val lines = listOf(line("9:32", 1128, 2560), line("58", 1128, 2510))
        assertEquals(emptyList<TimeAssigner.Marker>(), TimeLabels.markers(lines, listOf(bubble)))
    }

    @Test fun toleratesOcrSeparatorAndSpacing() {
        assertEquals("09:05", TimeLabels.markers(listOf(line("오전9.05", 20, 100)), emptyList()).single().time)
        assertEquals("00:10", TimeLabels.markers(listOf(line("오전 12 : 10", 20, 100)), emptyList()).single().time)
    }

    // 실측 오독: "오전 11:36"이 "오전 1:36"으로 읽힘 — 두 자리 시 라벨만큼 넓으니 숫자가 빠진 것. 위치만 남기고 미상.
    @Test fun blanksLabelWithDroppedHourDigit() {
        val lines = listOf(
            line("오전 11:19", 1128, 500, right = 1298),
            line("오전 1:36", 1128, 1500, right = 1298),
            line("오전 9:05", 1128, 2000, right = 1270),
        )
        assertEquals(listOf("11:19", "", "09:05"), TimeLabels.markers(lines, emptyList()).map { it.time })
    }

    // chat_info(시각 표시 자리)를 알면 그 밖의 시각 모양 글자(예: 사진 속 "오후 3:00")는 라벨이 아니다.
    @Test fun acceptsOnlyLinesInsideLabelAreas() {
        val area = FrameAssembler.Bubble("", left = 1115, right = 1290, top = 1028, bottom = 1148)
        val lines = listOf(line("오후 5:48", 1120, 1090, right = 1280, bottom = 1140), line("오후 3:00", 300, 700))
        assertEquals(listOf("17:48"), TimeLabels.markers(lines, emptyList(), listOf(area)).map { it.time })
    }
}
