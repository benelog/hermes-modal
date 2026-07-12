package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Test

class KakaoTimeTest {
    @Test fun koreanAfternoon() = assertEquals("15:01", KakaoTime.normalize("오후 3:01"))

    @Test fun koreanMorning() = assertEquals("09:05", KakaoTime.normalize("오전 9:05"))

    // 오후 12시(정오)는 12시 그대로, 오전 12시(자정)는 00시.
    @Test fun noonAndMidnight() {
        assertEquals("12:30", KakaoTime.normalize("오후 12:30"))
        assertEquals("00:30", KakaoTime.normalize("오전 12:30"))
    }

    // 영문 UI: AM/PM이 앞이나 뒤에 붙는다.
    @Test fun englishAmPm() {
        assertEquals("15:01", KakaoTime.normalize("3:01 PM"))
        assertEquals("15:01", KakaoTime.normalize("PM 3:01"))
        assertEquals("00:10", KakaoTime.normalize("12:10 am"))
    }

    // 24시간 표기(오전/오후 없음)는 그대로.
    @Test fun plain24h() {
        assertEquals("15:01", KakaoTime.normalize("15:01"))
        assertEquals("09:00", KakaoTime.normalize("9:00"))
    }

    @Test fun invalidYieldsBlank() {
        assertEquals("", KakaoTime.normalize(null))
        assertEquals("", KakaoTime.normalize(""))
        assertEquals("", KakaoTime.normalize("어제"))
        assertEquals("", KakaoTime.normalize("25:00")) // 시 범위 밖
        assertEquals("", KakaoTime.normalize("오후 13:00")) // 12시간제 범위 밖
    }

    // 시각 오귀속은 늦은 값으로만 튀므로 이른 값이 남아야 한다. 빈값은 채워진다.
    @Test fun earliestPrefersKnownThenSmaller() {
        assertEquals("09:05", KakaoTime.earliest("09:05", "14:20"))
        assertEquals("09:05", KakaoTime.earliest("14:20", "09:05"))
        assertEquals("14:20", KakaoTime.earliest("", "14:20"))
        assertEquals("14:20", KakaoTime.earliest("14:20", ""))
        assertEquals("", KakaoTime.earliest("", ""))
    }
}
