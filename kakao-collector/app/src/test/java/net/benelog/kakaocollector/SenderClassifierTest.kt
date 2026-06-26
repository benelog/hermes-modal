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

    // ── 오수집 회귀: 스크롤/새 메시지 도착 애니메이션 중 흔들린 bounds로 남 말풍선이
    //    내 말풍선으로 오판되던 케이스(2026-06 다량 오수집의 원인). 내 말풍선은 두 불변량을
    //    모두 만족한다: (1) 좌측이 충분히 우측 + (2) 우측이 화면 끝에 밀착. 하나만 만족하면 남이다.

    @Test fun jitteredLeftButNotHuggingRightEdgeIsNotOwn() {
        // 거터의 남 말풍선이 흔들려 left가 우측대(0.25↑)로 읽혔지만, 우측은 화면 끝에
        // 밀착하지 않음(rm=264, 0.20) → 내 말풍선 아님. (구버전은 rm<lm 만으로 내것 오판)
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 1344, left = 450, right = 1080))
    }

    @Test fun marginallyRightShiftedBelowOwnMinIsNotOwn() {
        // left/SW=0.268 — 구임계(0.25)는 넘지만 내-메시지-최소(0.34)에는 한참 못 미침.
        // 우측 밀착이어도 좌측이 거터~내것 사이라 남 말풍선의 흔들림으로 본다.
        assertFalse(SenderClassifier.isClearlyOwnMessage(screenWidth = 1344, left = 360, right = 1290))
    }
}
