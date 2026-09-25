package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageTextsTest {
    private fun img(top: Int, bottom: Int) = FrameAssembler.Bubble("", left = 177, right = 993, top = top, bottom = bottom)

    // 실측 배치(Pixel 10 Pro XL): 목록 390..2992, 공지 배너 390..641, 입력창 2688..2992.
    private val viewport = 390..2992
    private val overlays = listOf(390..641, 2688..2992)

    @Test fun onlyFullyVisiblePhotosAreRead() {
        val clippedAtTop = img(390, 775)   // 목록 위끝에 잘림(배너 밑)
        val underInput = img(2500, 2900)   // 입력창에 가림
        val whole = img(900, 1400)
        assertEquals(listOf(whole), ImageTexts.fullyVisible(listOf(clippedAtTop, underInput, whole), viewport, overlays))
    }

    @Test fun joinsOcrLinesIntoPrefixedMessage() {
        assertEquals(
            "[사진 속 글자] 채식주의자 한강 장편소설 창비",
            ImageTexts.messageText(listOf("채식주의자", " 한강  장편소설", "창비")),
        )
    }

    // 인물/풍경 사진의 잡음 한두 글자는 메시지로 만들지 않는다.
    @Test fun ignoresPhotosWithAlmostNoText() {
        assertNull(ImageTexts.messageText(listOf("E", "·")))
        assertNull(ImageTexts.messageText(emptyList()))
    }
}
