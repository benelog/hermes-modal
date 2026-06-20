package net.benelog.kakaocollector

/** 대상 방 목록(여러 줄 문자열) 파싱 + 화면 제목 매칭. Android 비의존(순수). */
object RoomMatch {
    /** 줄바꿈 구분 raw 문자열 → 방 제목 목록(trim, 빈 줄 제거). */
    fun parse(raw: String): List<String> =
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** 화면 제목이 대상 중 하나를 포함하면 그 대상(정규형)을 반환, 없으면 null. */
    fun match(title: String, targets: List<String>): String? =
        targets.firstOrNull { it.isNotEmpty() && title.contains(it) }
}
