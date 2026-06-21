package net.benelog.kakaocollector

/**
 * 카카오톡 접근성 트리에서 "내 메시지" 여부를 판정하는 보수적 휴리스틱.
 *
 * 닉네임 노드가 없는 메시지를 무조건 우측 정렬 여부만으로 ownName에 귀속하면,
 * 긴 좌측 말풍선이 화면 오른쪽 가까이까지 뻗는 경우 내 메시지로 오수집될 수 있다.
 * 따라서 우측 여백이 좌측 여백보다 작다는 조건에 더해, 말풍선/본문의 왼쪽 경계가
 * 화면의 오른쪽 영역에 충분히 들어온 경우만 내 메시지로 본다.
 */
object SenderClassifier {
    private const val OWN_LEFT_THRESHOLD_RATIO = 0.42f

    fun isClearlyOwnMessage(screenWidth: Int, left: Int, right: Int): Boolean {
        if (screenWidth <= 0 || right <= left) return false
        val leftMargin = left
        val rightMargin = screenWidth - right
        val startsInRightArea = left >= (screenWidth * OWN_LEFT_THRESHOLD_RATIO).toInt()
        return startsInRightArea && rightMargin < leftMargin
    }
}
