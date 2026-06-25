package net.benelog.kakaocollector

/**
 * 메시지 한 건의 보낸이를 정한다(순수). 중복 오수집의 핵심 원인이던 "보낸이 흔들림"을 막는다.
 *
 * 카톡은 연속된 같은 사람 메시지 묶음의 '첫 줄에만' 닉네임을 띄운다. 기존 코드는 walk 중
 * 마지막으로 본 닉네임([curSender])을 폴백으로 썼는데, 그 닉네임이 화면 밖으로 스크롤되면
 * 엉뚱한 사람으로 귀속돼(같은 메시지가 스크롤마다 다른 사람으로) 중복 행이 생겼다.
 *
 * 대신 여기서는 좌표 기반으로 정한다:
 *  - 우측 정렬(기하학적으로 '내 말풍선')이면 ownName.
 *  - 아니면 그 메시지 '위쪽에서 가장 가까운' 실제 닉네임. 답장 헤더("Reply to …"/"…에게 답장")는
 *    사람이 아니라 UI 라벨이므로 후보에서 제외한다.
 *  - 위에 마땅한 닉네임이 없으면 null → 호출부는 '추정 불가'로 보고 스킵(섣불리 귀속하지 않음).
 *    그 메시지는 닉네임이 함께 보이는 다음 스크롤에서 올바르게 잡힌다.
 */
object SenderAssigner {
    data class NickMarker(val top: Int, val name: String)

    /** 답장 헤더 닉네임(사람이 아닌 UI 라벨)인가. */
    fun isReplyLabel(name: String): Boolean =
        name.startsWith("Reply to") || name.contains("에게 답장")

    fun assign(
        screenW: Int,
        left: Int,
        right: Int,
        top: Int,
        ownName: String,
        nicks: List<NickMarker>,
    ): String? {
        if (SenderClassifier.isClearlyOwnMessage(screenW, left, right)) {
            return ownName.ifEmpty { null }
        }
        return nicks.filter { !isReplyLabel(it.name) && it.top <= top }
            .maxByOrNull { it.top }
            ?.name
    }
}
