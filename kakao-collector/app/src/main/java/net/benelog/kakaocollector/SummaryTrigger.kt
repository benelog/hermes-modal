package net.benelog.kakaocollector

/**
 * 방 메시지가 "나를 멘션하며 요약을 요청한" 명령인지 판정한다. Android 비의존(순수).
 *
 * 트리거 조건: 텍스트에 멘션 키워드(예: "정상혁")와 요약 키워드(예: "요약")가 모두 들어 있고,
 * 봇 자신이 보낸 메시지(마커 포함)가 아니어야 한다(루프 방지).
 */
object SummaryTrigger {
    fun isTrigger(
        text: String,
        mentionKeyword: String,
        summaryKeyword: String,
        botMarker: String,
    ): Boolean {
        if (mentionKeyword.isBlank() || summaryKeyword.isBlank()) return false
        // 봇이 보낸 요약은 마커를 달고 나가므로 명령으로 보지 않는다(루프 방지).
        if (botMarker.isNotBlank() && text.contains(botMarker)) return false
        return text.contains(mentionKeyword) && text.contains(summaryKeyword)
    }
}
