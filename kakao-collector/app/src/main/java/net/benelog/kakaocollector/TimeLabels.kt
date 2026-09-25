package net.benelog.kakaocollector

/**
 * 화면 OCR 결과에서 말풍선 옆 시각 라벨("오후 9:26")만 골라 [TimeAssigner.Marker]로 만든다(순수).
 *
 * 카톡은 시각 라벨을 접근성 트리에 노출하지 않는다(2026-09-26 uiautomator 전체 덤프에도 없음 —
 * 캔버스에 직접 그림). 그래서 정지 확인된 프레임의 스크린샷을 OCR해 라벨 위치를 얻는다.
 *  - '오전/오후'가 붙은 것만 받는다: 접두 없이 "9:26"만 읽히면 12시간제 오전/오후를 알 수 없다.
 *  - 말풍선(본문 노드) 영역과 겹치는 줄은 버린다 — 본문 속 "내일 오후 3:00에…"는 라벨이 아니다.
 *  - 시(時) 자릿수 누락 오독을 버린다: 2026-09-26 실측 "오전 11:36"이 "오전 1:36"으로 읽혔다.
 *    같은 글꼴의 라벨이라 한 자리 시 라벨이 두 자리 시 라벨만큼 넓으면 숫자가 빠진 것이다.
 *    (위아래 라벨과의 순서 모순은 [TimeAssigner.consistent]가 한 번 더 거른다.)
 *  - 오독 라벨은 버리지 않고 시각만 비운 마커(time="")로 남긴다 — 그 라벨의 말풍선 묶음이 아래쪽
 *    다른 묶음의 라벨(더 늦은 시각)을 대신 집지 않고 '미상'이 되도록.
 */
object TimeLabels {
    /** OCR 한 줄(텍스트 + 화면 좌표). */
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    // OCR이 ':'를 '.'/전각 콜론으로 읽는 경우까지 허용(오전/오후 접두가 있어 오판 여지는 작다).
    private val LABEL = Regex("""(오전|오후)\s*(\d{1,2})\s*[:：.]\s*(\d{2})""")

    // 폭 비교 여유(px): 한 자리 시 라벨은 두 자리 시 라벨보다 숫자 하나(수십 px)만큼 좁아야 한다.
    private const val WIDTH_SLACK_PX = 4

    private class Candidate(val marker: TimeAssigner.Marker, val hourDigits: Int, val width: Int)

    fun markers(lines: List<Line>, bubbles: List<FrameAssembler.Bubble>): List<TimeAssigner.Marker> {
        val candidates = lines.mapNotNull { line ->
            if (bubbles.any { overlaps(line, it) }) return@mapNotNull null
            val m = LABEL.find(line.text) ?: return@mapNotNull null
            val (ampm, hh, mm) = m.destructured
            val time = KakaoTime.normalize("$ampm $hh:$mm")
            if (time.isEmpty()) null else Candidate(TimeAssigner.Marker(top = line.top, time = time), hh.length, line.right - line.left)
        }
        val narrowestTwoDigit = candidates.filter { it.hourDigits == 2 }.minOfOrNull { it.width }
        return candidates.map {
            val digitDropped = it.hourDigits == 1 && narrowestTwoDigit != null && it.width + WIDTH_SLACK_PX >= narrowestTwoDigit
            if (digitDropped) it.marker.copy(time = "") else it.marker
        }
    }

    private fun overlaps(l: Line, b: FrameAssembler.Bubble): Boolean =
        l.left < b.right && b.left < l.right && l.top < b.bottom && b.top < l.bottom
}
