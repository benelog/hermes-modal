package net.benelog.kakaocollector

/**
 * 카카오톡 접근성 트리에서 "내 메시지" 여부를 판정하는 휴리스틱.
 *
 * 두 실패모드를 가르는 결정적 신호는 말풍선의 '왼쪽 경계'다(실측, Pixel 10 Pro XL SW=1344):
 *  - 남 말풍선은 본문 길이/미디어와 무관하게 항상 아바타 거터에서 시작한다(L≈192, 비율 ≈0.14 고정).
 *    넓은 남 말풍선이 오른쪽 끝까지 뻗어도 왼쪽은 거터에 남는다(→ 우측 정렬만 보면 오수집).
 *  - 내 말풍선은 우측에 밀착(오른쪽 여백 작음)하고 왼쪽 경계가 멀리 우측에서 시작한다
 *    (최대폭 긴 내 메시지여도 비율 ≈0.34 이상).
 *
 * 따라서 거터(≈0.14)와 내-메시지-최소(≈0.34)의 구조적 간격에 임계값을 둔다. 우측 여백<좌측 여백
 * (우측 정렬)이고 왼쪽 경계가 그 임계를 넘는 경우만 내 메시지로 본다. 임계가 너무 높으면(과거 0.42)
 * 긴 내 메시지(0.405)를 거꾸로 잘라 직전 발신자로 오귀속했다.
 */
object SenderClassifier {
    // 거터 비율(≈0.14)보다 충분히 높고 내-메시지-최소(≈0.34)보다 충분히 낮은 값.
    private const val OWN_LEFT_THRESHOLD_RATIO = 0.25f

    fun isClearlyOwnMessage(screenWidth: Int, left: Int, right: Int): Boolean {
        if (screenWidth <= 0 || right <= left) return false
        val leftMargin = left
        val rightMargin = screenWidth - right
        val startsInRightArea = left >= (screenWidth * OWN_LEFT_THRESHOLD_RATIO).toInt()
        return startsInRightArea && rightMargin < leftMargin
    }
}
