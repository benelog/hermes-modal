package net.benelog.kakaocollector

/**
 * 카카오톡 접근성 트리에서 "내 메시지" 여부를 판정하는 휴리스틱.
 *
 * 내 말풍선은 두 가지 '공간 불변량'을 **동시에** 만족한다(실측, Pixel 10 Pro XL SW=1344):
 *  (1) 좌측경계가 아바타 거터(L≈192, 0.14)보다 한참 우측 — 가장 긴 내 메시지도 0.34~0.40.
 *  (2) 우측이 화면 오른쪽 끝에 밀착 — 길이와 무관히 작은 우측 패딩만 남긴다(rm 비율 ≲0.04).
 * 남 말풍선은 (1)을 못 채우거나(거터 시작), 길어서 우측 끝까지 뻗어도 좌측은 거터에 남는다.
 *
 * 과거엔 (1)의 임계(0.25) + "우측여백<좌측여백"만 봤는데, 이 약한 조건은 스크롤/새 메시지
 * 도착 애니메이션으로 남 말풍선의 bounds가 흔들려 left가 잠깐 우측대로 읽히면 그대로 내것으로
 * 오판했다(2026-06 다량 오수집의 직접 원인). 그래서 **두 불변량을 모두** 요구한다 — 흔들림이
 * 하나(예: left만)를 우연히 만족시켜도, 우측 밀착까지 동시에 흉내내긴 어렵다. 한계: bounds가
 * 내 말풍선 위치를 통째로 흉내내는 프레임은 여전히 통과할 수 있어, 표집 자체를 안정화하는
 * settle(스크롤·CONTENT_CHANGED)과 함께 방어한다.
 */
object SenderClassifier {
    // 거터(0.14)보다 충분히 높고 가장 긴 내 메시지(0.34~0.40)는 안 자르는 좌측 임계.
    private const val OWN_LEFT_MIN_RATIO = 0.30f
    // 내 말풍선이 우측 끝에 밀착했다고 볼 우측여백 상한(실측 rm≈0.04, 여유 두어 0.12).
    private const val OWN_RIGHT_MARGIN_MAX_RATIO = 0.12f

    fun isClearlyOwnMessage(screenWidth: Int, left: Int, right: Int): Boolean {
        if (screenWidth <= 0 || right <= left) return false
        val leftMargin = left
        val rightMargin = screenWidth - right
        val startsWellRight = leftMargin >= (screenWidth * OWN_LEFT_MIN_RATIO).toInt()
        val hugsRightEdge = rightMargin <= (screenWidth * OWN_RIGHT_MARGIN_MAX_RATIO).toInt()
        return startsWellRight && hugsRightEdge
    }
}
