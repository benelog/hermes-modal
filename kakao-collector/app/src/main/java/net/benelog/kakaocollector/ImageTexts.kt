package net.benelog.kakaocollector

/**
 * 사진 말풍선의 글자(OCR)를 대화 메시지로 만드는 규칙(순수). 책 표지·책장·캡처처럼 글자가 담긴
 * 사진을 요약에 반영하기 위한 것 — 서버 요약 프롬프트는 `[사진 속 글자] …`를 사진에서 읽은 글자로 다룬다.
 *
 * dedupe 키는 본문이라, 같은 사진이 언제 읽혀도 같은 글자가 나와야 행이 갈라지지 않는다. 그래서
 *  - 목록 안에 '온전히' 보이는 사진만 대상으로 한다: 화면 끝에 잘리거나 공지 배너/입력창에 가린 사진은
 *    일부 글자만 읽혀 다른 키가 된다(접근성 bounds는 보이는 부분으로 잘려 나온다).
 *  - 호출자는 스크린샷에서 사진 영역만 잘라 따로 OCR한다(같은 픽셀 → 같은 결과).
 */
object ImageTexts {
    const val PREFIX = "[사진 속 글자] "

    // 사진 속 글자로 인정할 최소 글자 수(한글·영문·숫자). 인물/풍경 사진의 잡음 한두 글자는 버린다.
    private const val MIN_LETTERS = 4
    private const val MAX_CHARS = 300
    // 목록 경계에 이 픽셀 이내로 붙은 사진은 잘린 것으로 본다.
    private const val EDGE_PX = 2

    /** 목록([viewport]) 안에 온전히 보이고 어떤 겹친 UI([overlays])에도 가리지 않은 사진. */
    fun fullyVisible(
        images: List<FrameAssembler.Bubble>,
        viewport: IntRange?,
        overlays: List<IntRange>,
    ): List<FrameAssembler.Bubble> =
        images.filter { img ->
            val insideList = viewport == null ||
                (img.top > viewport.first + EDGE_PX && img.bottom < viewport.last - EDGE_PX)
            insideList && overlays.none { img.top <= it.last && it.first <= img.bottom }
        }

    /** 사진 한 장의 OCR 줄들 → 메시지 본문. 글자가 너무 적으면 null(메시지로 만들지 않음). */
    fun messageText(ocrLines: List<String>): String? {
        val joined = ocrLines.joinToString(" ") { it.trim() }.replace(Regex("""\s+"""), " ").trim()
        if (joined.count { it.isLetterOrDigit() } < MIN_LETTERS) return null
        return PREFIX + joined.take(MAX_CHARS)
    }
}
