package net.benelog.kakaocollector

/**
 * 카톡 날짜 구분 텍스트("2026. 06. 24. Wed" / "2026년 6월 22일 …")를 ISO 날짜(YYYY-MM-DD)로.
 * 못 읽으면 ""(빈 client_time 과 호환). 분 단위 시각은 이 카톡 버전이 접근성에 노출하지 않아
 * 수집 불가라, 대신 일 단위 날짜를 client_time 에 담는다.
 */
object KakaoDate {
    private val RE = Regex("""(\d{4})\D+(\d{1,2})\D+(\d{1,2})""")

    fun normalize(raw: String?): String {
        val m = RE.find(raw ?: "") ?: return ""
        val (y, mo, d) = m.destructured
        return "%04d-%02d-%02d".format(y.toInt(), mo.toInt(), d.toInt())
    }

    /** epoch millis → 기기 시간대 기준 ISO 날짜(YYYY-MM-DD). 백필 기간 지정에 사용. */
    fun isoOf(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    /** 두 ISO 날짜(YYYY-MM-DD)가 정확히 하루 차이인가. 어느 쪽이든 못 읽으면 false. */
    fun isAdjacentDay(a: String, b: String): Boolean = try {
        val days = java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.parse(a),
            java.time.LocalDate.parse(b),
        )
        days == 1L || days == -1L
    } catch (e: Exception) {
        false
    }
}
