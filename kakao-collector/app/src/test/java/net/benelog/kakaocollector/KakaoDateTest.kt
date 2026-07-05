package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun adjacentDays() {
        assertTrue(KakaoDate.isAdjacentDay("2026-07-04", "2026-07-05"))
        assertTrue(KakaoDate.isAdjacentDay("2026-07-05", "2026-07-04"))
        assertTrue(KakaoDate.isAdjacentDay("2026-06-30", "2026-07-01")) // 월 경계
    }

    @Test fun nonAdjacentOrUnparseableDays() {
        assertFalse(KakaoDate.isAdjacentDay("2026-07-04", "2026-07-04")) // 같은 날
        assertFalse(KakaoDate.isAdjacentDay("2026-07-03", "2026-07-05")) // 이틀 차
        assertFalse(KakaoDate.isAdjacentDay("", "2026-07-05"))
        assertFalse(KakaoDate.isAdjacentDay("2026-07-04", ""))
    }
}
