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

    /**
     * 순서가 모순되는 라벨을 버린다. 같은 날(날짜 구분선 사이) 안에서 시각은 위→아래로 줄지 않으므로,
     * 구간마다 가장 긴 비감소 부분열만 남긴다 — OCR 오독(예: 11:36→1:36) 한 개가 섞여도 이웃 라벨들이
     * 그것을 밀어낸다. 시각 병합은 '더 이른 값 우선'이라, 이른 쪽 오독은 걸러내지 않으면 굳어진다.
     * 모순 라벨은 위치는 남기고 시각만 비운다(time="") — 그 묶음은 다른 라벨을 집지 않고 미상이 된다.
     */
    fun consistent(markers: List<Marker>, dateTops: List<Int>): List<Marker> {
        val dates = dateTops.sorted()
        val sorted = markers.sortedBy { it.top }
        val keep = sorted.filter { it.time.isNotEmpty() }
            .groupBy { m -> dates.count { it <= m.top } } // 위에 있는 구분선 개수 = 날짜 구간 번호
            .values
            .flatMap { longestNonDecreasing(it) }
            .toSet()
        return sorted.map { if (it in keep) it else it.copy(time = "") }
    }

    private fun longestNonDecreasing(seq: List<Marker>): List<Marker> {
        if (seq.size < 2) return seq
        val len = IntArray(seq.size) { 1 }
        val prev = IntArray(seq.size) { -1 }
        for (i in seq.indices) {
            for (j in 0 until i) {
                if (seq[j].time <= seq[i].time && len[j] + 1 > len[i]) {
                    len[i] = len[j] + 1
                    prev[i] = j
                }
            }
        }
        val out = ArrayList<Marker>()
        var k = len.indices.maxByOrNull { len[it] } ?: return seq
        while (k >= 0) {
            out.add(seq[k])
            k = prev[k]
        }
        return out.asReversed()
    }

    fun assign(
        markers: List<Marker>,
        dateTops: List<Int>,
        messageTops: List<Int>,
        toleranceUp: Int = 8, // 라벨 top이 말풍선 top보다 몇 px 위로 렌더링되는 편차 허용
    ): List<String> {
        val sorted = consistent(markers, dateTops)
        val dates = dateTops.sorted()
        return messageTops.map { y ->
            val m = sorted.firstOrNull { it.top >= y - toleranceUp } ?: return@map ""
            val crossesDay = dates.any { it > y && it <= m.top }
            if (crossesDay) "" else m.time
        }
    }
}
