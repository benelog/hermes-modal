package net.benelog.kakaocollector

/**
 * 화면 속 시각 라벨(top Y + HH:MM)로 각 메시지(top Y)의 발신 시각을 정한다. 순수 함수.
 *
 * 카톡은 '같은 분(分)에 보낸 연속 말풍선 묶음의 마지막 말풍선' 옆에만 시각을 띄운다.
 * 따라서 메시지의 시각 = 자기 위치 '아래(또는 같은 줄)'에서 가장 가까운 시각 라벨.
 * [DateAssigner]와 같은 보수 원칙: 확신 없으면 ""(미상) — 빈 시각은 재수집에서 제자리
 * 승급되지만 시각은 dedupe 키가 아니므로 틀려도 행이 갈라지진 않는다. 그래도
 *  - 메시지와 라벨 사이에 날짜 구분선이 끼면(다른 날 묶음의 라벨) 쓰지 않는다.
 *  - 아래쪽에 라벨이 아예 없으면(화면 하단 잘림) 미상으로 둔다.
 */
object TimeAssigner {
    data class Marker(val top: Int, val time: String)

    fun assign(
        markers: List<Marker>,
        dateTops: List<Int>,
        messageTops: List<Int>,
        toleranceUp: Int = 8, // 라벨 top이 말풍선 top보다 몇 px 위로 렌더링되는 편차 허용
    ): List<String> {
        val sorted = markers.sortedBy { it.top }
        val dates = dateTops.sorted()
        return messageTops.map { y ->
            val m = sorted.firstOrNull { it.top >= y - toleranceUp } ?: return@map ""
            val crossesDay = dates.any { it > y && it <= m.top }
            if (crossesDay) "" else m.time
        }
    }
}
