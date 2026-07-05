package net.benelog.kakaocollector

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        Settings.init(this) // Application에서 이미 했지만 방어적으로(멱등).
        Uploader.init(this, RETENTION_MS)
        // 재시작해도 최근 수집분을 '이미 봄'으로 인식 → 재전송/재발화 방지.
        seen.addAll(Uploader.recentKeys(SEEN_CAP))
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
     *  PASS 1: 메시지(본문+좌표)·닉네임(top Y)·날짜 마커(top Y)를 모은다(즉시 submit 안 함).
     *  PASS 2: 좌표로 각 메시지의 날짜([DateAssigner])와 보낸이([SenderAssigner])를 정하고,
     *          sender 를 뺀 dedupe 키로 1차 차단 후 [Uploader.submit]. 보낸이를 못 정하면(닉네임이
     *          화면 밖) 그 메시지는 스킵 — 다음 스크롤에서 닉네임과 함께 보일 때 잡혀 오귀속/중복을 막는다.
     * 동시에 '맨 아래(최신) 새 메시지'를 추적해 allowTrigger면 멘션 요약 명령인지 판정한다.
     */
    private fun scrape(allowTrigger: Boolean, fromScroll: Boolean) {
        val ownName = Settings.ownName
        val nameId = Settings.nameId
        val msgId = Settings.msgId
        val dateId = Settings.dateIndicatorId
        val rect = android.graphics.Rect()

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

        // PASS 2 — 날짜·보낸이 결합 후 dedupe & submit.
        val dates = DateAssigner.assign(dateMarkers, pending.map { it.top })
        var newCount = 0
        var bottomY = Int.MIN_VALUE
        var bottomText = ""
        var bottomIsNew = false
        for ((i, p) in pending.withIndex()) {
            val date = dates[i]
            val sender = SenderAssigner.assign(screenW, p.left, p.right, p.top, ownName, nicks)
            val key = DedupeKey.of(activeRoom, p.text, date)
            val isNew = !seen.contains(key)
            if (sender != null && isNew) {
                firstSeen(key) // seen에 추가(+상한 정리)
                newCount++
                Uploader.submit(activeRoom, sender, p.text, date, fromScroll)
            }
            // 트리거는 본문만 보므로 보낸이 미상 메시지도 bottom 후보엔 넣되, '새 발화'는 수집된 경우만.
            if (p.bottom > bottomY) {
                bottomY = p.bottom
                bottomText = p.text
                bottomIsNew = isNew && sender != null
            }
        }
        if (newCount > 0) Log.i(TAG, "posted $newCount new message(s)")

        // 맨 아래(최신) 메시지가 '새것'이고 멘션 요약 명령이면 발화(열린 방 → 입력창으로 발신).
        if (allowTrigger && bottomIsNew && bottomText.isNotEmpty()) {
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
