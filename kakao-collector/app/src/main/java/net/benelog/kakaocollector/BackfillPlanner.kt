package net.benelog.kakaocollector

/**
 * 백필 수집의 진행/종료 판정(순수 함수). 상태·타이머는 [BackfillController]가 들고,
 * 판정 규칙만 여기 모아 단위 테스트한다. 날짜는 ISO(YYYY-MM-DD) 문자열 — 사전순 == 시간순.
 */
object BackfillPlanner {

    /**
     * SEEK(과거로 이동) 종료: 화면에서 관찰된 가장 과거 날짜가 from '이전 날'이면
     * from 당일의 시작 구분선이 화면 안(또는 아래)까지 들어온 것 — 여기서부터 아래로
     * 수집하면 from 당일 첫 메시지부터 누락이 없다. 날짜 미상("")이면 계속 올라간다.
     */
    fun seekDone(minVisibleDate: String, fromDate: String): Boolean =
        minVisibleDate.isNotEmpty() && minVisibleDate < fromDate

    /**
     * COLLECT(아래로 수집) 종료: 화면의 가장 과거 날짜조차 to '다음 날' 이후면
     * [from, to] 구간은 전부 지나왔다. 날짜 미상("")이면 계속 내려간다(맨 아래 도달은
     * no-progress 로 별도 종료).
     */
    fun collectDone(minVisibleDate: String, toDate: String): Boolean =
        minVisibleDate.isNotEmpty() && minVisibleDate > toDate

    /** 두 날짜 관찰값(빈값 허용) 중 더 과거. 프레임 날짜와 스크롤 중 스티키 뱃지 관찰을 합칠 때 사용. */
    fun minDate(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> minOf(a, b)
    }
}
