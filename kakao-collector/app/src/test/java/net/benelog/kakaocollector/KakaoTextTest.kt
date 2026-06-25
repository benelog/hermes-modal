package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoTextTest {
    @Test fun stripsReplyPrefix() =
        assertEquals("파란책 내용 좋네요.", KakaoText.clean("답장 메시지 파란책 내용 좋네요."))

    @Test fun trimsAndStripsPrefix() =
        assertEquals("안녕", KakaoText.clean("  답장 메시지   안녕 "))

    @Test fun leavesPlainText() =
        assertEquals("그냥 메시지", KakaoText.clean("그냥 메시지"))

    @Test fun blankStaysBlank() =
        assertEquals("", KakaoText.clean(""))

    @Test fun longerContinuationExtends() =
        assertTrue(KakaoText.extends("오픈은 10시더라고 정말로", "오픈은 10시더라고 정말로 그래서 줄섰다"))

    @Test fun tooShortIsNotConfident() =
        assertFalse(KakaoText.extends("안녕", "안녕 반가워요 오랜만입니다"))

    @Test fun stripsTrailingEllipsisBeforeCompare() =
        assertTrue(KakaoText.extends("충분히 긴 시작 문장인데…", "충분히 긴 시작 문장인데 계속 이어집니다"))

    @Test fun nonPrefixIsNotExtension() =
        assertFalse(KakaoText.extends("완전히 다른 시작 문장", "전혀 관계 없는 다른 문장"))

    @Test fun equalIsNotExtension() =
        assertFalse(KakaoText.extends("같은 길이의 문장입니다요", "같은 길이의 문장입니다요"))
}
