package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Test

class KakaoDateTest {
    @Test fun dottedEnglishWeekday() =
        assertEquals("2026-06-24", KakaoDate.normalize("2026. 06. 24. Wed"))

    @Test fun koreanFullDate() =
        assertEquals("2026-06-22", KakaoDate.normalize("2026년 6월 22일 일요일"))

    @Test fun zeroPadsSingleDigits() =
        assertEquals("2026-06-02", KakaoDate.normalize("2026. 6. 2. Mon"))

    @Test fun blankWhenNoDate() {
        assertEquals("", KakaoDate.normalize(""))
        assertEquals("", KakaoDate.normalize("Reaction, 2"))
    }
}
