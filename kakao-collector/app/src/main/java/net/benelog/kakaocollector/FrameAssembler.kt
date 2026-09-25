package net.benelog.kakaocollector

/**
 * scrape PASS 2 — 한 settle 프레임의 원시 관찰값([Snapshot])을 좌표로 결합해 완성된
 * 메시지 목록으로 만든다. 접근성 트리 순회(PASS 1)는 [KakaoCollectorService]가 하고,
 * 여기는 순수 계산만 있어 단위 테스트 대상이다.
 *
 *  - 말풍선을 화면 위→아래로 정렬(RecyclerView 재활용으로 자식 순서가 뒤섞일 수 있는데,
 *    위→아래 순으로 제출해야 서버 received_at이 대화 순서를 따라간다 — 특히 백필 COLLECT).
 *  - 각 메시지에 날짜([DateAssigner])·시각([TimeAssigner])·보낸이([SenderAssigner])를 결합.
 *    보낸이를 못 정하면(닉네임이 화면 밖) sender=null — 호출자가 그 메시지를 스킵해
 *    다음 스크롤에서 닉네임과 함께 보일 때 잡는다(오귀속/중복 방지).
 *  - 백필 판정용 부산물: 화면에서 확인된 가장 과거 날짜([AssembledFrame.minDate])와
 *    진행 감지 서명([AssembledFrame.signature]).
 */
object FrameAssembler {

    /** PASS 1에서 모은 메시지 후보(라벨 뗀 본문 + 말풍선 좌표 + 편집 라벨 여부). */
    data class Bubble(
        val text: String,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val edited: Boolean = false,
    )

    /** 한 프레임의 원시 관찰값(PASS 1 결과). screenWidth는 말풍선 bounds와 같은 좌표계. */
    data class Snapshot(
        val screenWidth: Int,
        val bubbles: List<Bubble>,
        val nicknames: List<SenderAssigner.NickMarker>,
        val dateMarkers: List<DateAssigner.Marker>,
        val timeMarkers: List<TimeAssigner.Marker>,
    )

    /** 날짜·시각·보낸이가 결합된 메시지. date/sentTime은 미상이면 "". */
    data class Message(
        val text: String,
        val date: String,
        val sentTime: String,
        val sender: String?,
        val top: Int,
        val bottom: Int,
    )

    data class AssembledFrame(
        val messages: List<Message>, // 화면 위→아래 순
        val minDate: String,
        val signature: String,
    )

    fun assemble(snapshot: Snapshot, ownName: String): AssembledFrame {
        val bubbles = snapshot.bubbles.sortedBy { it.top }
        val tops = bubbles.map { it.top }
        val dates = DateAssigner.assign(snapshot.dateMarkers, tops)
        val sentTimes = TimeAssigner.assign(snapshot.timeMarkers, snapshot.dateMarkers.map { it.top }, tops)
        val messages = bubbles.mapIndexed { i, b ->
            Message(
                text = b.text,
                date = dates[i],
                sentTime = sentTimes[i],
                sender = SenderAssigner.assign(
                    snapshot.screenWidth, b.left, b.right, b.top, ownName, snapshot.nicknames, b.edited,
                ),
                top = b.top,
                bottom = b.bottom,
            )
        }
        val minDate = (snapshot.dateMarkers.map { it.date } + dates).fold("", KnownValue::earliest)
        val signature = bubbles.firstOrNull()?.let { "${it.top}#${it.text}" } ?: "empty"
        return AssembledFrame(messages, minDate, signature)
    }

    /**
     * 두 스냅샷의 말풍선·닉네임 배치(본문+좌표)가 같은가 — 수집 전 '화면 정지' 확인용.
     * 보낸이(내/남 정렬, 위쪽 닉네임)는 좌표로만 정하므로, 애니메이션 중 흔들리는 bounds를 읽으면
     * 오귀속된다. 잠시 뒤 다시 읽어 같으면 정지한 화면으로 본다.
     * 날짜/시각 마커는 비교하지 않는다: 스티키 날짜 뱃지는 스크롤이 멎으면 곧 사라지는 오버레이라
     * 비교에 넣으면 정지한 화면도 '변함'으로 보고, 뱃지가 사라진 뒤 프레임만 남아 날짜를 잃는다.
     */
    fun sameGeometry(a: Snapshot, b: Snapshot): Boolean =
        a.screenWidth == b.screenWidth && a.bubbles == b.bubbles && a.nicknames == b.nicknames
}
