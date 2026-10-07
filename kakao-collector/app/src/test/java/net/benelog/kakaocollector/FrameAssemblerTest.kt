package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {
    private val screenW = 1080

    private fun bubble(text: String, top: Int, own: Boolean = false) =
        if (own) {
            FrameAssembler.Bubble(text, left = 500, right = 1060, top = top, bottom = top + 80)
        } else {
            FrameAssembler.Bubble(text, left = 40, right = 600, top = top, bottom = top + 80)
        }

    @Test fun sortsBubblesTopToBottomAndCombinesMarkers() {
        // RecyclerView 재활용으로 뒤섞인 순서가 화면 위→아래로 정렬돼야 한다.
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("아래 메시지", top = 900), bubble("위 메시지", top = 300)),
            nicknames = listOf(SenderAssigner.NickMarker(top = 250, name = "친구")),
            dateMarkers = listOf(DateAssigner.Marker(top = 100, date = "2026-07-01")),
            timeMarkers = emptyList(),
        )
        val frame = FrameAssembler.assemble(snapshot, ownName = "나")
        assertEquals(listOf("위 메시지", "아래 메시지"), frame.messages.map { it.text })
        assertEquals(listOf("2026-07-01", "2026-07-01"), frame.messages.map { it.date })
        assertEquals(listOf("친구", "친구"), frame.messages.map { it.sender })
    }

    @Test fun ownBubbleGetsOwnName() {
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("내가 쓴 글", top = 300, own = true)),
            nicknames = listOf(SenderAssigner.NickMarker(top = 250, name = "친구")),
            dateMarkers = emptyList(),
            timeMarkers = emptyList(),
        )
        assertEquals("나", FrameAssembler.assemble(snapshot, ownName = "나").messages.single().sender)
    }

    @Test fun senderUnknownWhenNoNicknameAbove() {
        // 닉네임이 화면 밖 → sender=null → 호출자가 스킵(오귀속 방지).
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("누가 썼는지 모름", top = 100)),
            nicknames = emptyList(),
            dateMarkers = emptyList(),
            timeMarkers = emptyList(),
        )
        assertNull(FrameAssembler.assemble(snapshot, ownName = "나").messages.single().sender)
    }

    @Test fun minDateIsOldestVisibleDate() {
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("메시지", top = 500)),
            nicknames = listOf(SenderAssigner.NickMarker(top = 450, name = "친구")),
            dateMarkers = listOf(
                DateAssigner.Marker(top = 100, date = "2026-07-02"),
                DateAssigner.Marker(top = 400, date = "2026-07-03"),
            ),
            timeMarkers = emptyList(),
        )
        assertEquals("2026-07-02", FrameAssembler.assemble(snapshot, ownName = "나").minDate)
    }

    @Test fun signatureComesFromTopmostBubble() {
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("아래", top = 800), bubble("맨 위", top = 200)),
            nicknames = emptyList(),
            dateMarkers = emptyList(),
            timeMarkers = emptyList(),
        )
        assertEquals("200#맨 위", FrameAssembler.assemble(snapshot, ownName = "나").signature)
    }

    @Test fun emptyFrameHasEmptySignature() {
        val snapshot = FrameAssembler.Snapshot(screenW, emptyList(), emptyList(), emptyList(), emptyList())
        val frame = FrameAssembler.assemble(snapshot, ownName = "나")
        assertEquals("empty", frame.signature)
        assertEquals("", frame.minDate)
        assertEquals(emptyList<FrameAssembler.Message>(), frame.messages)
    }

    @Test fun sentTimeLabelBelowIsAttached() {
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("메시지", top = 300)),
            nicknames = listOf(SenderAssigner.NickMarker(top = 250, name = "친구")),
            dateMarkers = emptyList(),
            timeMarkers = listOf(TimeAssigner.Marker(top = 320, time = "15:01")),
        )
        assertEquals("15:01", FrameAssembler.assemble(snapshot, ownName = "나").messages.single().sentTime)
    }

    private fun snapshot(vararg bubbles: FrameAssembler.Bubble, dates: List<DateAssigner.Marker> = emptyList()) =
        FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = bubbles.toList(),
            nicknames = listOf(SenderAssigner.NickMarker(top = 250, name = "친구")),
            dateMarkers = dates,
            timeMarkers = emptyList(),
        )

    // 정지 확인: 같은 배치면 정지, 말풍선이 몇 px라도 움직였으면(애니메이션 중) 아직 흔들리는 화면.
    @Test fun sameGeometryDetectsMovingBubbles() {
        val a = snapshot(bubble("메시지", top = 300))
        assertTrue(FrameAssembler.sameGeometry(a, snapshot(bubble("메시지", top = 300))))
        assertFalse(FrameAssembler.sameGeometry(a, snapshot(bubble("메시지", top = 296))))
        // 삽입 애니메이션 중 left가 흔들린 프레임(내 말풍선처럼 보임)도 '다름'.
        assertFalse(FrameAssembler.sameGeometry(a, snapshot(bubble("메시지", top = 300, own = true))))
    }

    // 스티키 날짜 뱃지는 곧 사라지는 오버레이라 정지 판정에 넣지 않는다(넣으면 날짜 있는 프레임을 버리게 됨).
    @Test fun sameGeometryIgnoresTransientDateBadge() {
        val withBadge = snapshot(bubble("메시지", top = 300), dates = listOf(DateAssigner.Marker(100, "2026-07-01")))
        assertTrue(FrameAssembler.sameGeometry(withBadge, snapshot(bubble("메시지", top = 300))))
    }

    // 정지 화면엔 날짜 노드가 없다 → 같은 배치였던 뱃지 프레임의 날짜를 빌려 실시간 수집분도 날짜를 얻는다.
    @Test fun borrowsBadgeDatesFromSameGeometryFrame() {
        val badge = snapshot(bubble("메시지", top = 300), dates = listOf(DateAssigner.Marker(100, "2026-10-07")))
        val still = FrameAssembler.withBadgeDates(snapshot(bubble("메시지", top = 300)), badge)
        assertEquals(listOf("2026-10-07"), still.dateMarkers.map { it.date })
    }

    // 목록이 움직인 뒤(배치 다름)면 마커 Y가 무효 → 빌리지 않고 빈 날짜(틀린 날짜는 중복 행을 만든다).
    @Test fun doesNotBorrowBadgeDatesWhenGeometryDiffers() {
        val badge = snapshot(bubble("메시지", top = 300), dates = listOf(DateAssigner.Marker(100, "2026-10-07")))
        assertTrue(FrameAssembler.withBadgeDates(snapshot(bubble("메시지", top = 120)), badge).dateMarkers.isEmpty())
        assertTrue(FrameAssembler.withBadgeDates(snapshot(bubble("메시지", top = 300)), null).dateMarkers.isEmpty())
    }

    // 정지 프레임이 스스로 날짜를 봤으면 그게 더 최신 — 그대로 둔다.
    @Test fun keepsOwnDatesOverBadgeFrame() {
        val badge = snapshot(bubble("메시지", top = 300), dates = listOf(DateAssigner.Marker(100, "2026-10-06")))
        val own = snapshot(bubble("메시지", top = 300), dates = listOf(DateAssigner.Marker(100, "2026-10-07")))
        assertEquals(listOf("2026-10-07"), FrameAssembler.withBadgeDates(own, badge).dateMarkers.map { it.date })
    }

    // 공지 배너에 아래끝이 가려진 말풍선은 자기 시각 라벨이 안 보인다 → 아래 다른 묶음 라벨을 집지 않고 미상.
    @Test fun sentTimeBlankWhenBubbleBottomIsUnderOverlay() {
        val snapshot = FrameAssembler.Snapshot(
            screenWidth = screenW,
            bubbles = listOf(bubble("배너 밑", top = 390), bubble("보이는 것", top = 900)),
            nicknames = listOf(SenderAssigner.NickMarker(top = 300, name = "친구")),
            dateMarkers = emptyList(),
            timeMarkers = listOf(TimeAssigner.Marker(top = 950, time = "21:32")),
            overlays = listOf(390..641),
        )
        assertEquals(listOf("", "21:32"), FrameAssembler.assemble(snapshot, ownName = "나").messages.map { it.sentTime })
    }
}
