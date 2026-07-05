package net.benelog.kakaocollector

/**
 * 화면 속 날짜 마커(top Y + 날짜)로 각 메시지(top Y)의 날짜를 정한다. 순수 함수.
 * 각 메시지엔 자기 위쪽에서 가장 가까운 마커의 날짜를 부여한다.
 *
 * 확신이 없으면 ""(미상)을 준다 — 빈 날짜 행은 다음 재수집에서 제자리 승급(빈값→날짜)되지만,
 * 틀린 날짜는 (room,text,client_time) 키를 갈라 같은 메시지를 중복 행으로 만든다(2026-07-05 실측).
 *  - 위에 마커가 없는 메시지: 미상. 인라인 구분선은 '그 날의 시작'이므로 그 위 메시지는 이전
 *    날짜인데, 예전처럼 최상단 마커 날짜를 내려받으면 하루 늦게 찍힌다.
 *  - 아래쪽 구분선과의 모순: 구분선 아래부터가 날짜 D이므로 그 위 메시지는 D 이전이어야 한다.
 *    위에서 받은 날짜가 아래 구분선 날짜 이상이면(스크롤 중 스티키 뱃지가 이전 화면 날짜로
 *    지연된 프레임) 그 날짜는 믿지 않는다.
 */
object DateAssigner {
    data class Marker(val top: Int, val date: String)

    fun assign(markers: List<Marker>, messageTops: List<Int>): List<String> {
        val sorted = markers.sortedBy { it.top }
        return messageTops.map { y ->
            val above = sorted.lastOrNull { it.top <= y } ?: return@map ""
            val below = sorted.firstOrNull { it.top > y }
            if (below != null && above.date >= below.date) "" else above.date
        }
    }
}
