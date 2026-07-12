package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAssignerTest {
    // 시각 라벨은 같은 분(分) 묶음의 마지막 말풍선 옆 — 메시지 아래(또는 같은 줄)에서
    // 가장 가까운 라벨이 그 메시지의 시각이다. 묶음 전체가 같은 라벨을 공유한다.
    @Test fun nearestLabelBelowSharedByGroup() {
        val markers = listOf(
            TimeAssigner.Marker(top = 400, time = "09:05"),
            TimeAssigner.Marker(top = 900, time = "09:12"),
        )
        val msgTops = listOf(100, 300, 700)
        assertEquals(
            listOf("09:05", "09:05", "09:12"),
            TimeAssigner.assign(markers, emptyList(), msgTops),
        )
    }

    // 라벨이 메시지 위에만 있으면(자기 묶음의 라벨이 화면 하단에 잘림) 미상.
    @Test fun labelOnlyAboveYieldsBlank() {
        val markers = listOf(TimeAssigner.Marker(top = 100, time = "09:05"))
        assertEquals(listOf(""), TimeAssigner.assign(markers, emptyList(), listOf(500)))
    }

    @Test fun noLabelsYieldBlank() =
        assertEquals(listOf("", ""), TimeAssigner.assign(emptyList(), emptyList(), listOf(100, 200)))

    // 메시지와 아래쪽 라벨 사이에 날짜 구분선이 끼면 그 라벨은 '다른 날' 묶음 것 → 미상.
    @Test fun labelAcrossDaySeparatorIsRejected() {
        val markers = listOf(TimeAssigner.Marker(top = 800, time = "00:10"))
        val dateTops = listOf(500) // 자정 구분선이 메시지(200)와 라벨(800) 사이
        assertEquals(
            listOf("", "00:10"),
            TimeAssigner.assign(markers, dateTops, listOf(200, 600)),
        )
    }

    // 라벨이 말풍선 top보다 몇 px 위에 렌더링되는 편차는 허용(같은 줄로 취급).
    @Test fun sameRowToleranceUp() {
        val markers = listOf(TimeAssigner.Marker(top = 296, time = "21:40"))
        assertEquals(listOf("21:40"), TimeAssigner.assign(markers, emptyList(), listOf(300)))
    }
}
