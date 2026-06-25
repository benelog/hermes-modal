package net.benelog.kakaocollector

/**
 * 화면 속 날짜 마커(top Y + 날짜)로 각 메시지(top Y)의 날짜를 정한다. 순수 함수.
 * 각 메시지엔 자기 위쪽에서 가장 가까운 마커의 날짜를 부여하고, 위에 마커가 없으면
 * 최상단 마커(=sticky 뱃지) 날짜. 마커가 하나도 없으면 ""(미상).
 */
object DateAssigner {
    data class Marker(val top: Int, val date: String)

    fun assign(markers: List<Marker>, messageTops: List<Int>): List<String> {
        if (markers.isEmpty()) return messageTops.map { "" }
        val sorted = markers.sortedBy { it.top }
        val topmost = sorted.first().date
        return messageTops.map { y ->
            sorted.lastOrNull { it.top <= y }?.date ?: topmost
        }
    }
}
