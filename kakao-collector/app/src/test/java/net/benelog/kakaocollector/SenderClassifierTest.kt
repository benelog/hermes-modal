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
}
