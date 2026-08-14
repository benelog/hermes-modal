package net.benelog.kakaocollector

/**
 * 카톡 알림 extras에서 (대상 방, 명령 본문)을 고르는 순수 로직 — 방이 닫혀 있어도
 * 알림 경로로 멘션 요약 명령을 잡기 위한 것. Notification 파싱(Android 의존)은
 * [KakaoCollectorService]가 하고, 후보 선택 규칙만 여기서 판정한다.
 */
object NotificationCommand {

    data class Command(val room: String, val body: String)

    /**
     * 방 이름 후보는 그룹 대화 제목 우선(convTitle → subText → title)으로 대상 방에 매칭하고,
     * 명령 본문 후보는 요약된 큰 본문 우선(bigText → text → title)으로 고른다.
     * 대상 방이 아니거나 본문이 없으면 null.
     */
    fun parse(
        convTitle: String,
        subText: String,
        title: String,
        bigText: String,
        text: String,
        targets: List<String>,
    ): Command? {
        val room = listOf(convTitle, subText, title)
            .firstNotNullOfOrNull { c -> c.takeIf { it.isNotEmpty() }?.let { RoomMatch.match(it, targets) } }
            ?: return null
        val body = listOf(bigText, text, title).firstOrNull { it.isNotEmpty() } ?: return null
        return Command(room, body)
    }
}
