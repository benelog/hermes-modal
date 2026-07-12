package net.benelog.kakaocollector

/**
 * 카톡 말풍선 옆 시각 텍스트("오후 3:01" / "10:05 AM" / "15:01")를 24시간 "HH:MM"으로.
 * 못 읽으면 ""(시각 미상 — 빈 sent_time 과 호환, 재수집 때 제자리 승급).
 * 카톡 버전에 따라 시각 노드가 접근성 트리에 노출되지 않을 수 있어 빈값이 기본 상태다.
 */
object KakaoTime {
    private val RE = Regex("""(오전|오후|AM|PM|am|pm)?\s*(\d{1,2}):(\d{2})\s*(AM|PM|am|pm)?""")

    fun normalize(raw: String?): String {
        val m = RE.find(raw ?: "") ?: return ""
        val (prefix, hh, mm, suffix) = m.destructured
        val marker = prefix.ifEmpty { suffix }.uppercase()
        var h = hh.toInt()
        val min = mm.toInt()
        if (min !in 0..59) return ""
        when (marker) {
            "오후", "PM" -> { if (h !in 1..12) return ""; if (h != 12) h += 12 }
            "오전", "AM" -> { if (h !in 1..12) return ""; if (h == 12) h = 0 }
            else -> if (h !in 0..23) return ""
        }
        return "%02d:%02d".format(h, min)
    }

    /**
     * 두 시각(HH:MM, 빈값 허용) 중 더 이른 쪽. 시각 오귀속은 항상 '늦은' 값으로만 튄다
     * (자기 라벨이 트리에 없으면 그 아래=더 나중 묶음의 라벨을 집는 구조) — 이른 값이 진실에 가깝다.
     */
    fun earliest(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> minOf(a, b)
    }
}
