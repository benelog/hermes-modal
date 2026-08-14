package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
