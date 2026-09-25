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

    // '수정됨' 라벨은 스크랩마다 붙었다 말았다 해 같은 메시지를 두 행으로 갈랐다.
    @Test fun stripsEditedLabel() {
        assertEquals("학교는 교육기관", KakaoText.clean("수정됨 학교는 교육기관"))
        assertEquals("고친 답글", KakaoText.clean("답장 메시지 수정됨 고친 답글"))
        assertEquals("고친 답글", KakaoText.clean("수정됨 답장 메시지 고친 답글"))
        assertEquals("내용 수정됨 끝", KakaoText.clean("내용 수정됨 끝")) // 본문 중간은 그대로
    }

    @Test fun detectsEditedLabel() {
        assertTrue(KakaoText.isEdited("수정됨 학교는 교육기관"))
        assertTrue(KakaoText.isEdited("답장 메시지 수정됨 고친 답글"))
        assertFalse(KakaoText.isEdited("답장 메시지 그냥 답글"))
        assertFalse(KakaoText.isEdited("내용 수정됨 끝"))
    }

    @Test fun leavesPlainText() =
        assertEquals("그냥 메시지", KakaoText.clean("그냥 메시지"))

    @Test fun blankStaysBlank() =
        assertEquals("", KakaoText.clean(""))

    @Test fun longerContinuationExtends() =
        assertTrue(KakaoText.isExtendedBy("오픈은 10시더라고 정말로", "오픈은 10시더라고 정말로 그래서 줄섰다"))

    @Test fun tooShortIsNotConfident() =
        assertFalse(KakaoText.isExtendedBy("안녕", "안녕 반가워요 오랜만입니다"))

    @Test fun stripsTrailingEllipsisBeforeCompare() =
        assertTrue(KakaoText.isExtendedBy("충분히 긴 시작 문장인데…", "충분히 긴 시작 문장인데 계속 이어집니다"))

    @Test fun nonPrefixIsNotExtension() =
        assertFalse(KakaoText.isExtendedBy("완전히 다른 시작 문장", "전혀 관계 없는 다른 문장"))

    @Test fun equalIsNotExtension() =
        assertFalse(KakaoText.isExtendedBy("같은 길이의 문장입니다요", "같은 길이의 문장입니다요"))
}
