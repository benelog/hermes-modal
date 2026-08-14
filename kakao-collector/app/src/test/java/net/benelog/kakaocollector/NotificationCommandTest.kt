package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCommandTest {
    private val targets = listOf("아카라카북클럽")

    @Test fun groupConversationTitleWinsForRoom() {
        val cmd = NotificationCommand.parse(
            convTitle = "아카라카북클럽", subText = "", title = "보낸이",
            bigText = "@정상혁 요약해줘", text = "짧은 본문", targets = targets,
        )
        assertEquals(NotificationCommand.Command("아카라카북클럽", "@정상혁 요약해줘"), cmd)
    }

    @Test fun fallsBackToSubTextThenTitleForRoom() {
        val cmd = NotificationCommand.parse(
            convTitle = "", subText = "아카라카북클럽 (12)", title = "보낸이",
            bigText = "", text = "본문", targets = targets,
        )
        assertEquals("아카라카북클럽", cmd?.room)
    }

    @Test fun bodyPrefersBigTextOverText() {
        val cmd = NotificationCommand.parse(
            convTitle = "아카라카북클럽", subText = "", title = "보낸이",
            bigText = "긴 전체 본문", text = "잘린 본문", targets = targets,
        )
        assertEquals("긴 전체 본문", cmd?.body)
    }

    @Test fun nonTargetRoomIsIgnored() {
        assertNull(
            NotificationCommand.parse(
                convTitle = "다른 방", subText = "", title = "다른 방",
                bigText = "본문", text = "", targets = targets,
            ),
        )
    }

    @Test fun emptyBodyIsIgnored() {
        assertNull(
            NotificationCommand.parse(
                convTitle = "아카라카북클럽", subText = "", title = "",
                bigText = "", text = "", targets = targets,
            ),
        )
    }
}
