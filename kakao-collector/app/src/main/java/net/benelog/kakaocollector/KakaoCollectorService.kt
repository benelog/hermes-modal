package net.benelog.kakaocollector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

/**
 * 카카오톡 화면을 접근성으로 읽어 대상 방 메시지를 수집한다.
 *
 * 수집: 대상 방이 화면에 있을 때(사용자가 열어 스크롤하는 동안) 보이는 말풍선을
 * 본문/보낸이/시각으로 골라 중복 제거 후 Modal /ingest 로 POST. 조용한 방(알림 끔)도 동작한다.
 *
 * 멘션 요약(발신): 방의 '맨 아래(최신) 새 메시지'가 멘션 키워드+요약 키워드를 담고 있으면
 * Modal /summarize(=Hermes LLM)로 요약을 받아 그 방으로 발신한다. 발신은 읽기 전용 원칙을
 * 깨므로 [Settings.autoReply]가 켜졌을 때만 동작하며, 입력창/전송버튼 id 캘리브레이션이 필요하다.
 */
class KakaoCollectorService : AccessibilityService() {

    companion object {
        const val TAG = "KakaoCollector"

        /**
         * 살아있는 서비스 인스턴스(없으면 null). [BackfillController]와 MainActivity가
         * 제스처/방 탐색/상태 확인에 쓴다. 시스템이 서비스를 바인딩/해제할 때만 갱신.
         */
        @Volatile
        var instance: KakaoCollectorService? = null
            private set

        private const val MIN_INTERVAL_MS = 500L
        // 스크롤이 멈춘 뒤 이 시간만큼 지나면 수집(스크롤 중 흔들리는 좌표로 오정렬하는 것 방지).
        private const val SCROLL_SETTLE_MS = 250L
        private const val SEEN_CAP = 3000
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30일
        // 요약문 발신: 텍스트 입력 후 카톡이 전송 버튼을 활성화할 시간을 준다.
        private const val SEND_CLICK_DELAY_MS = 400L
        // 같은 방 재트리거 최소 간격(중복/이중 발신 방지: scrape·알림 경로 공통).
        private const val TRIGGER_COOLDOWN_MS = 60_000L
        private const val NOTIF_CAP = 500
    }

    private val seen = LinkedHashSet<String>()

    // 백필 COLLECT 세션에서 '이미 제출한 키'(장기 seen과 별개) — 겹치는 프레임의 같은 메시지를
    // 세션당 1회만 제출/재전송하기 위한 것. 세션(COLLECT 단계) 시작 때 비운다.
    private val backfillSubmitted = HashSet<String>()
    private var lastRun = 0L

    // 카톡은 방 제목 노드를 '방을 열 때'만 트리에 노출하고 스크롤 중엔 빼버린다.
    // 그래서 매번 제목으로 판별하면 스크롤 중 수집이 끊긴다 → 방 입장 여부를 래치로 기억한다.
    private var inTargetRoom = false
    private var activeRoom = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    // 스크롤이 멈춘 뒤 한 번 수집한다 — 스크롤 중 프레임은 말풍선 bounds가 흔들려 내/남 정렬 오판 위험.
    // fromScroll=true: 스크롤(백필) 수집은 날짜 경계 오부여 가드([MessageStore]) 대상.
    private val settleRunnable = Runnable {
        lastRun = System.currentTimeMillis()
        handle(allowTrigger = false, fromScroll = true)
    }
    // 새 메시지 도착(CONTENT_CHANGED)도 삽입+하단 스크롤 애니메이션으로 bounds가 흔들리므로
    // 즉시 수집하지 않고 settle 후 1회 수집한다(스크롤과 동일 이유 — 내/남 오정렬=오수집 방지).
    // 트리거는 허용(멘션 요약은 settle 직후 발화; +250ms 지연은 무방).
    private val contentSettleRunnable = Runnable {
        lastRun = System.currentTimeMillis()
        handle(allowTrigger = true)
    }
    // 방별 마지막 트리거 시각(쿨다운 — scrape·알림 경로의 중복/이중 발신 방지).
    private val lastTrigger = ConcurrentHashMap<String, Long>()
    // 처리한 알림 키(같은 알림이 여러 번 와도 1회만 트리거).
    private val handledNotifs = LinkedHashSet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Settings.init(this) // Application에서 이미 했지만 방어적으로(멱등).
        Uploader.init(this, RETENTION_MS)
        // 재시작해도 최근 수집분을 '이미 봄'으로 인식 → 재전송/재발화 방지.
        seen.addAll(Uploader.recentKeys(SEEN_CAP))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        BackfillController.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        BackfillController.onServiceDisconnected()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != Config.KAKAO_PACKAGE) return
        when (event.eventType) {
            // 카톡 알림: 방이 닫혀 있어도 멘션 요약 명령을 잡아 알림 '답장'으로 발신.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event)
            // 화면 전환(방 열기)은 방 판별 기준점이라 레이트리밋 없이 처리. 단, 방을 '여는' 순간
            // 맨 아래에 오래전 명령이 있어도 발화하지 않도록 트리거는 허용하지 않는다(수집만).
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handle(allowTrigger = false)
            // 새 메시지 도착(CONTENT_CHANGED): 삽입/하단 스크롤 애니메이션이 끝난 뒤(settle) 수집한다.
            // 즉시 수집하면 흔들리는 bounds로 남 말풍선을 내것으로 오판(오수집)한다. rate-limit으로
            // anchor를 잡아(연속 변경에도 무한 연기 방지) settle 시각을 미뤄 애니메이션을 흘려보낸다.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastRun < MIN_INTERVAL_MS) return
                lastRun = now
                mainHandler.removeCallbacks(contentSettleRunnable)
                mainHandler.postDelayed(contentSettleRunnable, SCROLL_SETTLE_MS)
            }
            // 스크롤은 멈춘 뒤(settle) 한 번만 수집한다. 스크롤 중 프레임의 흔들리는 bounds로
            // 남 메시지를 내 것으로 오판(→오수집)하는 것을 막는다. 트리거는 스크롤에선 불허(백필 재발화 방지).
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 스티키 날짜 뱃지는 '스크롤 중'에만 트리에 뜬다 — 백필 진행 중이면 settle을
                // 기다리지 않고 여기서 날짜만 읽어 seek/collect 종료 판정에 보탠다(수집은 안 함).
                if (BackfillController.isRunning()) peekDatesForBackfill()
                mainHandler.removeCallbacks(settleRunnable)
                mainHandler.postDelayed(settleRunnable, SCROLL_SETTLE_MS)
            }
        }
    }

    override fun onInterrupt() {}

    private fun handle(allowTrigger: Boolean, fromScroll: Boolean = false) {
        val root = rootInActiveWindow ?: return
        try {
            if (Settings.calibrate) {
                dumpTree(root, 0)
                return
            }
            // 매 이벤트마다 '현재 방'을 툴바 제목(contentDescription)으로 식별한다 — 스크롤 중에도 안정적.
            // 대상이면 입장+활성방 갱신, '다른 방'이면 해제(stale 활성방으로 오태깅되는 것 방지),
            // 못 읽으면(드묾) 직전 상태 유지.
            val targets = Settings.roomNamesList()
            val title = currentRoomTitle(root)
            val matched = title?.let { RoomMatch.match(it, targets) }
            when {
                matched != null -> {
                    if (!inTargetRoom || activeRoom != matched) {
                        Log.i(TAG, "entered target room: $matched")
                        Uploader.flushUnsent() // 활동 재개 시점 — 밀린 미전송분(직전 세션 꼬리 등) 재시도
                    }
                    inTargetRoom = true
                    activeRoom = matched
                }
                title != null -> { // 현재 방을 읽었는데 대상이 아님 → 수집 중단.
                    inTargetRoom = false
                    activeRoom = ""
                }
                // title == null: 방 식별 불가 → 직전 상태 유지(섣불리 해제/입장하지 않음).
            }
            // 백필 세션에 방 판별 결과 통지(제목을 실제로 읽은 이벤트만 — 미상은 통지하지 않음).
            if (title != null) BackfillController.onRoomState(matched ?: "")
            if (inTargetRoom && activeRoom.isNotEmpty()) scrape(allowTrigger, fromScroll)
        } catch (e: Exception) {
            Log.w(TAG, "scrape error: ${e.message}")
        }
    }

    /**
     * 현재 방 제목. 1차: 툴바 제목 노드의 contentDescription(text는 비어도 cd에 '방이름+인원수'가
     * 스크롤 중에도 안정적으로 있음). 폴백: titleId(id/name) 텍스트(방 열 때만 뜸). 못 찾으면 null.
     */
    private fun currentRoomTitle(root: AccessibilityNodeInfo): String? {
        for (t in root.findAccessibilityNodeInfosByViewId(Config.TOOLBAR_TITLE_ID)) {
            t.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
            t.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val titleId = Settings.titleId
        if (titleId.isNotEmpty()) {
            for (t in root.findAccessibilityNodeInfosByViewId(titleId)) {
                t.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /** 말풍선 RecyclerView를 다른 윈도우에 둘 수 있어, getWindows()의 모든 윈도우 루트를 모은다. */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        return roots
    }

    /** PASS 1에서 모으는 메시지 후보(본문 + 말풍선 좌표). 날짜·보낸이는 PASS 2에서 좌표로 결합. */
    private data class Pending(
        val text: String,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
    )

    /**
     * 현재 화면의 말풍선을 2-pass로 수집한다.
     *  PASS 1: 메시지(본문+좌표)·닉네임(top Y)·날짜 마커(top Y)·시각 라벨(top Y)을 모은다(즉시 submit 안 함).
     *  PASS 2: 좌표로 각 메시지의 날짜([DateAssigner])·시각([TimeAssigner])·보낸이([SenderAssigner])를
     *          정하고, sender 를 뺀 dedupe 키로 1차 차단 후 [Uploader.submit]. 보낸이를 못 정하면(닉네임이
     *          화면 밖) 그 메시지는 스킵 — 다음 스크롤에서 닉네임과 함께 보일 때 잡혀 오귀속/중복을 막는다.
     * 동시에 '맨 아래(최신) 새 메시지'를 추적해 allowTrigger면 멘션 요약 명령인지 판정한다.
     *
     * 백필 세션 중엔 동작이 달라진다:
     *  - SEEK(과거로 이동): 수집하지 않고 프레임 관찰값만 [BackfillController]에 보고
     *    (여기서 수집하면 received_at 역순 적재 = 서버 대화 순서 붕괴).
     *  - COLLECT(아래로 수집): seen 캐시를 우회해 이미 수집된 메시지도 다시 제출 —
     *    [MessageStore.recordOrMerge]가 중복은 걸러내고 누락된 날짜/시각만 제자리 갱신.
     *  - 두 단계 모두 멘션 요약 트리거는 불허(백필로 지나가는 옛 명령 재발화 방지).
     */
    private fun scrape(allowTrigger: Boolean, fromScroll: Boolean) {
        val ownName = Settings.ownName
        val nameId = Settings.nameId
        val msgId = Settings.msgId
        val dateId = Settings.dateIndicatorId
        val timeId = Settings.timeId
        val rect = android.graphics.Rect()

        val seekOnly = BackfillController.seekActive(activeRoom)
        val backfillCollect = BackfillController.collectActive(activeRoom)

        val roots = collectRoots()
        // 화면폭은 말풍선 bounds와 '같은 좌표계'여야 내/남 정렬 판정이 맞다. 디스플레이 크기/밀도
        // override가 걸리면 displayMetrics.widthPixels가 getBoundsInScreen 좌표계와 어긋나
        // 남 메시지를 내 것으로 오판할 수 있으므로, 창 루트 bounds 폭(스크린 좌표계)을 쓴다.
        var screenW = 0
        for (r in roots) {
            val rr = android.graphics.Rect()
            r.getBoundsInScreen(rr)
            if (rr.width() > screenW) screenW = rr.width()
        }
        if (screenW <= 0) screenW = resources.displayMetrics.widthPixels

        // PASS 1 — 좌표와 함께 모은다.
        val pending = ArrayList<Pending>()
        val nicks = ArrayList<SenderAssigner.NickMarker>()
        val dateMarkers = ArrayList<DateAssigner.Marker>()
        val timeMarkers = ArrayList<TimeAssigner.Marker>()
        for (root in roots) {
            walk(root) { n ->
                val id = n.viewIdResourceName ?: ""
                // 카톡이 본문/닉네임을 text가 아니라 contentDescription에 두기도 한다 → text 우선, 없으면 cd.
                val value = nodeValue(n)
                if (value.isEmpty()) return@walk
                when {
                    nameId.isNotEmpty() && id == nameId -> {
                        n.getBoundsInScreen(rect)
                        nicks.add(SenderAssigner.NickMarker(top = rect.top, name = value))
                    }
                    dateId.isNotEmpty() && id == dateId -> {
                        val date = KakaoDate.normalize(value)
                        if (date.isNotEmpty()) {
                            n.getBoundsInScreen(rect)
                            dateMarkers.add(DateAssigner.Marker(top = rect.top, date = date))
                        }
                    }
                    timeId.isNotEmpty() && id == timeId -> {
                        // 발신 시각("오후 3:01"). 카톡 버전에 따라 트리에 없을 수 있음 — 있으면 수집.
                        val time = KakaoTime.normalize(value)
                        if (time.isNotEmpty()) {
                            n.getBoundsInScreen(rect)
                            timeMarkers.add(TimeAssigner.Marker(top = rect.top, time = time))
                        }
                    }
                    msgId.isNotEmpty() && id == msgId -> {
                        // 답장에 인용된 원문(영문 UI)은 중복이므로 건너뜀.
                        if (value.startsWith("Replied message") || value.startsWith("Original message")) {
                            return@walk
                        }
                        // "답장 메시지 " 접두는 떼고 본문(=실제 답글)만 둔다. 빈 본문은 스킵.
                        val text = KakaoText.clean(value)
                        if (text.isEmpty()) return@walk
                        n.getBoundsInScreen(rect)
                        pending.add(Pending(text = text, left = rect.left, right = rect.right, top = rect.top, bottom = rect.bottom))
                    }
                }
            }
        }
        // RecyclerView 자식 순서는 재활용으로 뒤섞일 수 있다 — 화면 위→아래 순으로 제출해야
        // 서버 received_at 이 대화 순서를 따라간다(특히 백필 COLLECT).
        pending.sortBy { it.top }

        // PASS 2 — 날짜·시각·보낸이 결합 후 dedupe & submit.
        val dates = DateAssigner.assign(dateMarkers, pending.map { it.top })
        val sentTimes = TimeAssigner.assign(timeMarkers, dateMarkers.map { it.top }, pending.map { it.top })
        var newCount = 0
        var bottomY = Int.MIN_VALUE
        var bottomText = ""
        var bottomIsNew = false
        for ((i, p) in pending.withIndex()) {
            val date = dates[i]
            val sender = SenderAssigner.assign(screenW, p.left, p.right, p.top, ownName, nicks)
            val key = DedupeKey.of(activeRoom, p.text, date)
            val isNew = !seen.contains(key)
            // 백필 COLLECT는 장기 seen 캐시를 우회해 재제출하되(DB 병합이 중복은 거르고 누락
            // 날짜/시각만 채움) 세션 내에서는 키당 1회만 — 겹치는 프레임의 중복 제출 방지.
            val shouldSubmit = when {
                seekOnly || sender == null -> false
                backfillCollect -> backfillSubmitted.add(key)
                else -> isNew
            }
            if (shouldSubmit && sender != null) {
                firstSeen(key) // seen에 추가(+상한 정리)
                if (isNew) newCount++
                val onOutcome: ((MessageStore.Outcome) -> Unit)? =
                    if (backfillCollect) BackfillController::onSubmitOutcome else null
                Uploader.submit(
                    activeRoom, sender, p.text, date, sentTimes[i], fromScroll,
                    // 로컬엔 완전해도(SKIPPED) 서버는 14일 보관으로 잃었을 수 있다 → 백필은 멱등 재전송.
                    repostOnSkip = backfillCollect,
                    onOutcome = onOutcome,
                )
            }
            // 트리거는 본문만 보므로 보낸이 미상 메시지도 bottom 후보엔 넣되, '새 발화'는 수집된 경우만.
            if (p.bottom > bottomY) {
                bottomY = p.bottom
                bottomText = p.text
                bottomIsNew = isNew && sender != null
            }
        }
        if (newCount > 0) Log.i(TAG, "posted $newCount new message(s)")

        // 백필 세션에 프레임 관찰값 보고: 화면에서 확인된 가장 과거 날짜 + 진행 감지 서명.
        if (seekOnly || backfillCollect) {
            val frameMinDate = BackfillPlanner.minDate(
                dateMarkers.minOfOrNull { it.date } ?: "",
                dates.filter { it.isNotEmpty() }.minOrNull() ?: "",
            )
            val signature = pending.firstOrNull()?.let { "${it.top}#${it.text}" } ?: "empty"
            BackfillController.onFrame(activeRoom, BackfillController.Frame(frameMinDate, signature))
        }

        // 맨 아래(최신) 메시지가 '새것'이고 멘션 요약 명령이면 발화(열린 방 → 입력창으로 발신).
        // 백필 중엔 불허 — 스크롤로 지나가는 '옛 명령'이 새것처럼 보여 재발화할 수 있다.
        if (allowTrigger && !seekOnly && !backfillCollect && bottomIsNew && bottomText.isNotEmpty()) {
            val room = activeRoom
            triggerSummary(room, bottomText) { msg -> mainHandler.post { sendToRoom(room, msg) } }
        }
    }

    /** 노드의 표시값: text 우선, 비었으면 contentDescription. 둘 다 trim. */
    private fun nodeValue(n: AccessibilityNodeInfo): String {
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return n.contentDescription?.toString()?.trim() ?: ""
    }

    /**
     * 명령이면 Modal로 요약을 받아 [deliver]로 전달한다. 자동발신이 켜져 있고, 멘션+요약 키워드를
     * 모두 담고(봇 마커 없음), 그 방의 쿨다운이 지났을 때만. [deliver]는 발신 방법(입력창/알림답장)을
     * 주입받아 scrape·알림 경로가 같은 트리거 로직을 공유한다.
     */
    private fun triggerSummary(room: String, command: String, deliver: (String) -> Unit) {
        if (!Settings.autoReply) return
        val mention = Settings.effectiveMention()
        if (!SummaryTrigger.isTrigger(command, mention, Settings.summaryKeyword, Settings.botMarker)) return
        val now = System.currentTimeMillis()
        val last = lastTrigger[room] ?: 0L
        if (now - last < TRIGGER_COOLDOWN_MS) return // 중복/이중 발신 방지
        lastTrigger[room] = now
        Log.i(TAG, "summary trigger room=$room cmd=${command.take(40)}")
        Summarizer.request(room, command) { res ->
            if (res.ok && res.summary.isNotBlank()) {
                deliver(Settings.botMarker + res.summary)
            } else {
                Log.w(TAG, "summary failed room=$room err=${res.error}")
            }
        }
    }

    /**
     * 카톡 알림에서 멘션 요약 명령을 잡아 발신한다. 방이 닫혀 있어도 동작하도록, 알림의 '답장'
     * 액션(RemoteInput)으로 그 방에 직접 답장한다(없으면 열린 방 입력창으로 폴백).
     */
    @Suppress("DEPRECATION")
    private fun handleNotification(event: AccessibilityEvent) {
        if (!Settings.autoReply) return
        val notification = event.parcelableData as? Notification ?: return
        val extras = notification.extras ?: return

        fun ex(key: String) = extras.getCharSequence(key)?.toString()?.trim() ?: ""
        val convTitle = ex(Notification.EXTRA_CONVERSATION_TITLE)
        val subText = ex(Notification.EXTRA_SUB_TEXT)
        val title = ex(Notification.EXTRA_TITLE)
        val bigText = ex(Notification.EXTRA_BIG_TEXT)
        val text = ex(Notification.EXTRA_TEXT)

        // 방 이름 후보(그룹 대화 제목 우선) → 대상 방으로 매칭. 대상이 아니면 무시.
        val targets = Settings.roomNamesList()
        val room = listOf(convTitle, subText, title)
            .firstNotNullOfOrNull { c -> c.takeIf { it.isNotEmpty() }?.let { RoomMatch.match(it, targets) } }
            ?: return
        // 명령 본문 후보(요약된 큰 본문 우선).
        val body = listOf(bigText, text, title).firstOrNull { it.isNotEmpty() } ?: return

        // 같은 알림이 여러 번 와도 1회만.
        val key = room + "" + body
        if (handledNotifs.contains(key)) return
        handledNotifs.add(key)
        if (handledNotifs.size > NOTIF_CAP) {
            val it = handledNotifs.iterator(); if (it.hasNext()) { it.next(); it.remove() }
        }

        val replyAction = findReplyAction(notification)
        val deliver: (String) -> Unit = if (replyAction != null) {
            { msg -> replyViaRemoteInput(replyAction, msg) }
        } else {
            // 답장 액션이 없으면(드묾) 열린 방 입력창으로 폴백.
            { msg -> mainHandler.post { sendToRoom(room, msg) } }
        }
        triggerSummary(room, body, deliver)
    }

    /** 알림 액션 중 RemoteInput(인라인 답장)을 가진 것을 찾는다. */
    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        for (a in actions) {
            val ris = a.remoteInputs
            if (ris != null && ris.isNotEmpty()) return a
        }
        return null
    }

    /** 알림의 답장 액션(RemoteInput)으로 그 방에 직접 답장한다(방을 열지 않아도 됨). */
    private fun replyViaRemoteInput(action: Notification.Action, text: String) {
        try {
            val ris = action.remoteInputs ?: return
            val intent = Intent()
            val results = Bundle()
            for (ri in ris) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(ris, intent, results)
            action.actionIntent.send(this, 0, intent)
            Log.i(TAG, "summary replied via notification RemoteInput")
        } catch (e: Exception) {
            Log.w(TAG, "remote input reply failed: ${e.message}")
        }
    }

    /**
     * 요약문을 현재 방의 입력창에 넣고 전송한다(접근성 발신). 발신 직전 '현재 방 == 트리거 방'을
     * 재확인해 다른 방 오발신을 막는다. main 스레드에서 호출되어야 한다.
     */
    private fun sendToRoom(room: String, text: String) {
        val titleRoot = rootInActiveWindow
        val title = titleRoot?.let { currentRoomTitle(it) }
        val matchedNow = title?.let { RoomMatch.match(it, Settings.roomNamesList()) }
        if (matchedNow != room) {
            Log.w(TAG, "send aborted: room changed (now=$matchedNow want=$room)")
            return
        }
        val inputId = Settings.inputId
        val sendId = Settings.sendId
        if (inputId.isBlank() || sendId.isBlank()) {
            Log.w(TAG, "send aborted: inputId/sendId 미설정")
            return
        }
        val input = findById(collectRoots(), inputId)
        if (input == null) {
            Log.w(TAG, "send aborted: 입력창 못 찾음 ($inputId)")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            Log.w(TAG, "send aborted: ACTION_SET_TEXT 실패")
            return
        }
        // 텍스트 입력 후 전송 버튼이 활성화될 시간을 주고 클릭.
        mainHandler.postDelayed({
            val sendBtn = findById(collectRoots(), sendId)
            if (sendBtn == null) {
                Log.w(TAG, "send: 전송버튼 못 찾음 ($sendId)")
                return@postDelayed
            }
            if (clickNodeOrAncestor(sendBtn)) {
                Log.i(TAG, "summary sent room=$room (${text.length} chars)")
            } else {
                Log.w(TAG, "send: 전송버튼 클릭 실패")
            }
        }, SEND_CLICK_DELAY_MS)
    }

    /** 여러 윈도우 루트에서 resource-id로 첫 노드를 찾는다. */
    private fun findById(roots: List<AccessibilityNodeInfo>, id: String): AccessibilityNodeInfo? {
        for (r in roots) {
            val found = r.findAccessibilityNodeInfosByViewId(id)
            if (found.isNotEmpty()) return found[0]
        }
        return null
    }

    /** 노드 또는 가까운 클릭가능 조상을 클릭한다. */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // ── 백필 수집 지원([BackfillController]가 main 스레드에서 호출) ──────────

    /** 지금 이 순간 '대상 방 화면'인가(패키지+툴바 제목 재확인). 다른 앱/방에 제스처 오발사 방지. */
    fun inRoomNow(room: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != Config.KAKAO_PACKAGE) return false
        val title = currentRoomTitle(root) ?: return false
        return RoomMatch.match(title, Settings.roomNamesList()) == room
    }

    /**
     * 카톡 화면에서 대상 방으로 진입 시도(자동 방문). 우선순위:
     *  (1) 다른 방 안이면(입력창 존재=방 화면) 뒤로 나가 목록으로,
     *  (2) 목록에서 방 제목으로 시작하는 행을 찾아 클릭,
     *  (3) 방 행이 안 보이면 '채팅' 탭 클릭(친구/더보기 탭에서 시작한 경우).
     * 진입 성공 여부는 이후 WINDOW_STATE_CHANGED → [handle]의 방 판별이 확정한다.
     */
    fun tryOpenRoom(room: String): Boolean {
        try {
            val roots = collectRoots()
            val inputId = Settings.inputId
            if (inputId.isNotBlank() && findById(roots, inputId) != null) {
                if (!inRoomNow(room)) performGlobalAction(GLOBAL_ACTION_BACK)
                return false
            }
            for (r in roots) {
                if (r.packageName?.toString() != Config.KAKAO_PACKAGE) continue
                for (n in r.findAccessibilityNodeInfosByText(room)) {
                    val v = nodeValue(n)
                    val id = n.viewIdResourceName ?: ""
                    if (!v.startsWith(room)) continue // 목록 행 제목은 '방이름(+인원수)'로 시작
                    // 툴바 제목/말풍선 본문에 방 이름이 있는 경우는 클릭 대상이 아니다.
                    if (id == Config.TOOLBAR_TITLE_ID || (Settings.msgId.isNotEmpty() && id == Settings.msgId)) continue
                    if (clickNodeOrAncestor(n)) {
                        Log.i(TAG, "backfill: 방 목록에서 '$room' 클릭")
                        return true
                    }
                }
            }
            for (r in roots) {
                if (r.packageName?.toString() != Config.KAKAO_PACKAGE) continue
                for (n in r.findAccessibilityNodeInfosByText("채팅")) {
                    val v = nodeValue(n)
                    if ((v == "채팅" || v.startsWith("채팅,") || v.startsWith("채팅 탭")) && clickNodeOrAncestor(n)) {
                        return false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryOpenRoom failed: ${e.message}")
        }
        return false
    }

    // 제스처가 실제로 수행됐는지(완료/취소) 확인용 — 실기기에서 스크롤 무반응 진단의 핵심 로그.
    private val gestureResultLogger = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.i(TAG, "backfill swipe: completed")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.w(TAG, "backfill swipe: CANCELLED (시스템/터치 간섭 — 노드 스크롤 폴백 대상)")
        }
    }

    /**
     * 세로 드래그 제스처. downward=true 면 손가락을 아래로(=과거 메시지 보기),
     * false 면 위로(=최신 방향). 화면 중앙 세로 구간(상단 툴바·하단 입력창 회피)만 쓴다.
     * 플링이 아니라 '끝까지 끌고 가는' 드래그라 한 걸음 거리(distanceRatio)가 정확히 지켜진다.
     */
    fun performSwipe(downward: Boolean, distanceRatio: Float, durationMs: Long): Boolean {
        val b = android.graphics.Rect()
        rootInActiveWindow?.getBoundsInScreen(b)
        if (b.width() <= 0 || b.height() <= 0) {
            b.set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        if (b.width() <= 0 || b.height() <= 0) return false
        val x = b.exactCenterX()
        val dist = b.height() * distanceRatio
        val yTop = b.exactCenterY() - dist / 2
        val yBottom = b.exactCenterY() + dist / 2
        val (startY, endY) = if (downward) yTop to yBottom else yBottom to yTop
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return try {
            dispatchGesture(gesture, gestureResultLogger, null)
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed: ${e.message}")
            false
        }
    }

    /**
     * 제스처 폴백: 채팅 리스트(가장 큰 스크롤 가능 노드)에 접근성 스크롤 액션을 직접 보낸다.
     * 합성 터치가 아니라 RecyclerView에 명령하는 방식이라 확실히 움직이는 대신, 걸음 크기가
     * '한 페이지'로 고정된다(겹침이 얇아 settle 프레임이 경계 메시지를 한 번만 본다 — 수집엔 충분).
     * 2026-07-13 실측: dispatchGesture가 true를 반환해도 카톡 리스트가 움직이지 않는 기기가 있어
     * [BackfillController]가 진행 없음을 감지하면 이 방식으로 자동 전환한다.
     */
    fun performNodeScroll(older: Boolean): Boolean {
        val node = findScrollableChatNode() ?: return false
        val action = if (older) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return try {
            node.performAction(action)
        } catch (e: Exception) {
            Log.w(TAG, "node scroll failed: ${e.message}")
            false
        }
    }

    /** 카톡 윈도우에서 화면을 가장 넓게 덮는 스크롤 가능 노드(=채팅 리스트)를 찾는다. */
    private fun findScrollableChatNode(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val rect = android.graphics.Rect()
        for (root in collectRoots()) {
            if (root.packageName?.toString() != Config.KAKAO_PACKAGE) continue
            walk(root) { n ->
                if (n.isScrollable) {
                    n.getBoundsInScreen(rect)
                    val area = rect.width().toLong() * rect.height()
                    if (area > bestArea) {
                        bestArea = area
                        best = n
                    }
                }
            }
        }
        return best
    }

    /** 백필 워치독용: settle 이벤트가 안 올 때 수동으로 한 프레임 수집을 돌린다. */
    fun forceScrape() {
        mainHandler.post { handle(allowTrigger = false, fromScroll = true) }
    }

    /** 백필 COLLECT 단계 시작: 세션 내 1회-제출 캐시를 비운다(이전 세션 것 제거). */
    fun resetBackfillDedupe() {
        backfillSubmitted.clear()
    }

    /** 백필 종료 알림 등 짧은 사용자 통지(어느 스레드에서 불러도 안전). */
    fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /** 스크롤 이벤트 순간의 스티키 날짜 뱃지만 빠르게 읽어 백필 판정에 보탠다(수집 아님). */
    private fun peekDatesForBackfill() {
        try {
            val root = rootInActiveWindow ?: return
            val dateId = Settings.dateIndicatorId
            if (dateId.isEmpty()) return
            for (n in root.findAccessibilityNodeInfosByViewId(dateId)) {
                val date = KakaoDate.normalize(nodeValue(n))
                if (date.isNotEmpty()) BackfillController.onDatePeek(date)
            }
        } catch (e: Exception) {
            // 스크롤 이벤트 빈도로 불리므로 조용히 무시(다음 이벤트에서 다시 시도).
        }
    }

    /** 처음 보는 key면 기억하고 true. 용량 상한 초과 시 가장 오래된 것부터 제거. */
    private fun firstSeen(key: String): Boolean {
        if (seen.contains(key)) return false
        seen.add(key)
        if (seen.size > SEEN_CAP) {
            val it = seen.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return true
    }

    private fun walk(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        node ?: return
        visit(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), visit)
        }
    }

    /** 캘리브레이션용: 현재 화면의 id/bounds/text를 Logcat에 덤프. */
    private fun dumpTree(node: AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        val id = node.viewIdResourceName
        val txt = node.text?.toString()
        val cd = node.contentDescription?.toString()
        if ((id != null && id.startsWith("com.kakao")) || (!txt.isNullOrEmpty()) || (!cd.isNullOrEmpty())) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            Log.i(TAG, " ".repeat(depth) + "id=" + id + " L=" + r.left + " R=" + r.right + " text=" + (txt ?: "") + " cd=" + (cd ?: ""))
        }
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1)
        }
    }
}
