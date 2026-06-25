package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderAssignerTest {
    private fun nick(top: Int, name: String) = SenderAssigner.NickMarker(top, name)

    // 내 메시지(우측 정렬, Pixel 10 Pro XL SW=1344 실측 좌표) → ownName.
    @Test fun ownRightAlignedUsesOwnName() {
        assertEquals(
            "정상혁",
            SenderAssigner.assign(
                screenW = 1344, left = 544, right = 1287, top = 800,
                ownName = "정상혁", nicks = listOf(nick(700, "이중윤")),
            ),
        )
    }

    // 남 메시지 → 자기 위쪽에서 가장 가까운 닉네임.
    @Test fun otherUsesNearestNicknameAbove() {
        assertEquals(
            "이중윤",
            SenderAssigner.assign(
                screenW = 1344, left = 192, right = 1018, top = 800,
                ownName = "정상혁", nicks = listOf(nick(700, "이중윤"), nick(200, "이효정")),
            ),
        )
    }

    // 여러 닉네임 중 메시지 바로 위(가장 가까운). 아래쪽 닉네임은 무시.
    @Test fun picksClosestNicknameAboveIgnoringBelow() {
        assertEquals(
            "이중윤",
            SenderAssigner.assign(
                screenW = 1344, left = 192, right = 1018, top = 800,
                ownName = "정상혁",
                nicks = listOf(nick(100, "이효정"), nick(700, "이중윤"), nick(900, "지민경")),
            ),
        )
    }

    // 답장 라벨 닉네임은 제외하고 그 위 실제 닉네임으로.
    @Test fun skipsReplyLabelNickname() {
        assertEquals(
            "이효정",
            SenderAssigner.assign(
                screenW = 1344, left = 192, right = 1018, top = 800,
                ownName = "정상혁",
                nicks = listOf(nick(200, "이효정"), nick(750, "이중윤_전기전자(기전4)97에게 답장")),
            ),
        )
    }

    // 위에 (실제) 닉네임이 없으면 추정 불가 → null(스킵; 오귀속 방지).
    @Test fun noNicknameAboveReturnsNull() {
        assertNull(
            SenderAssigner.assign(
                screenW = 1344, left = 192, right = 1018, top = 300,
                ownName = "정상혁", nicks = listOf(nick(700, "이중윤")),
            ),
        )
    }

    // 내 메시지인데 ownName 미설정 → null.
    @Test fun ownButNoOwnNameReturnsNull() {
        assertNull(
            SenderAssigner.assign(
                screenW = 1344, left = 544, right = 1287, top = 800,
                ownName = "", nicks = emptyList(),
            ),
        )
    }

    @Test fun replyLabelDetection() {
        assertTrue(SenderAssigner.isReplyLabel("Reply to 이효정"))
        assertTrue(SenderAssigner.isReplyLabel("Reply to Me"))
        assertTrue(SenderAssigner.isReplyLabel("이중윤_전기전자(기전4)97에게 답장"))
        assertFalse(SenderAssigner.isReplyLabel("이효정_인문(영문 국문)_97"))
    }
}
