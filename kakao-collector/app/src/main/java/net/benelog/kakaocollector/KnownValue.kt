package net.benelog.kakaocollector

/**
 * ""(미상)을 허용하는 사전순 == 시간순 문자열(ISO 날짜 YYYY-MM-DD, 시각 HH:MM) 공용 규칙.
 * 날짜·시각을 모두 '모르면 빈값'으로 다루므로, 비교/병합 규칙을 한 곳에 둔다.
 */
object KnownValue {
    /** 아는 값 중 더 이른 쪽. 한쪽만 알면 그 값, 둘 다 미상이면 "". */
    fun earliest(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> minOf(a, b)
    }

    /** 같은 값이거나 한쪽이 미상이면 같은 것으로 볼 수 있다 — 알려진 서로 다른 값끼리만 충돌. */
    fun compatible(a: String, b: String): Boolean = a == b || a.isEmpty() || b.isEmpty()
}
