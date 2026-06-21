package net.benelog.kakaocollector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderClassifierTest {
    @Test fun longLeftBubbleThatReachesRightEdgeIsNotOwn() {
        // 기존 휴리스틱(right margin < left margin)만 쓰면 40 < 180 이라 내 메시지로 오판했다.
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 1080, left = 180, right = 1040))
    }

    @Test fun clearlyRightAlignedBubbleIsOwn() {
        assertTrue(SenderClassifier.isClearlyOwnMessage(screenWidth = 1080, left = 560, right = 1040))
    }

    @Test fun leftAlignedBubbleIsNotOwn() {
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 1080, left = 80, right = 520))
    }

    @Test fun invalidBoundsAreNotOwn() {
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 0, left = 560, right = 1040))
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 1080, left = 560, right = 560))
    }

    // 아래 두 케이스는 Pixel 10 Pro XL(SW=1344) 실측 좌표다(uiautomator dump).
    // 남 말풍선은 본문/길이와 무관하게 아바타 거터에서 시작(L≈192, 비율 0.14),
    // 긴 내 말풍선은 우측 밀착(rm 작음)에 좌측경계가 멀리 우측(비율 0.34~).

    @Test fun longOwnMessageNearMaxWidthIsOwn() {
        // 실측 '은퇴 전에라도…' 긴 내 메시지. left/SW=0.405 가 0.42 임계에 살짝 못 미쳐
        // 직전 발신자로 오귀속되던 false-negative.
        assertTrue(SenderClassifier.isClearlyOwnMessage(screenWidth = 1344, left = 544, right = 1287))
    }

    @Test fun widestOtherBubbleAtAvatarGutterIsNotOwn() {
        // 실측 가장 넓은 남 말풍선도 좌측은 거터(L=192)에 고정 → 내 메시지 아님.
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 1344, left = 192, right = 1018))
    }
}
