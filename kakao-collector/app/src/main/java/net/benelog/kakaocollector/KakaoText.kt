package net.benelog.kakaocollector

/**
 * 수집 본문 정규화/비교(순수). 서버 collector_core.clean_text / extends 와 동일 규칙을 유지해
 * 폰·서버가 같은 본문을 만들고 같은 dedupe 결과를 내도록 한다.
 */
object KakaoText {
    // 답장 본문 접두 라벨("답장 메시지 …"). 뒤따르는 본문이 실제 답글 내용이므로 라벨만 제거.
    private val REPLY_PREFIX = Regex("""^\s*답장 메시지\s+""")

    // 잘림 표시("…더보기" 등). 접두 비교 전에 떼어낸다.
    private val TRUNC_TAIL = Regex("""(?:\.\.\.|…|더\s*보기)\s*$""")

    fun clean(text: String?): String =
        REPLY_PREFIX.replace(text ?: "", "").trim()

    private fun core(text: String): String =
        TRUNC_TAIL.replace(text, "").trimEnd()

    /**
     * [longer] 가 [shorter] 의 '더 완전한 버전'인가 — 같은 메시지가 더 길게 잡힌 경우
     * (잘렸다 펼쳐짐, 또는 '꼬리 달기'로 편집되어 길어짐). [shorter] 의 잘림 표시는 떼고 비교하며,
     * 우연한 짧은 일치로 서로 다른 메시지를 합치지 않도록 최소 길이를 둔다.
     */
    fun extends(shorter: String, longer: String, minLen: Int = 12): Boolean {
        val s = core(shorter)
        if (s.length < minLen || longer.length <= s.length) return false
        return longer.startsWith(s)
    }
}
