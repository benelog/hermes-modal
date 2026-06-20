package net.benelog.kakaocollector

import android.accessibilityservice.AccessibilityService
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
        private const val SEEN_CAP = 3000
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30일
        // 요약문 발신: 텍스트 입력 후 카톡이 전송 버튼을 활성화할 시간을 준다.
        private const val SEND_CLICK_DELAY_MS = 400L
    }

    private val seen = LinkedHashSet<String>()
    private var lastRun = 0L

    // 카톡은 방 제목 노드를 '방을 열 때'만 트리에 노출하고 스크롤 중엔 빼버린다.
    // 그래서 매번 제목으로 판별하면 스크롤 중 수집이 끊긴다 → 방 입장 여부를 래치로 기억한다.
    private var inTargetRoom = false
    private var activeRoom = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    // 방별 요약 진행중 플래그(동시 1건 제한, 발신 완료 시 해제).
    private val summarizing = ConcurrentHashMap.newKeySet<String>()

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
            // 화면 전환(방 열기)은 방 판별 기준점이라 레이트리밋 없이 처리. 단, 방을 '여는' 순간
            // 맨 아래에 오래전 명령이 있어도 발화하지 않도록 트리거는 허용하지 않는다(수집만).
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handle(allowTrigger = false)
            // 스크롤/내용변경은 폭주하므로 레이트리밋. 트리거는 '새 메시지 도착'을 뜻하는
            // CONTENT_CHANGED 일 때만 허용(스크롤 백필로 옛 명령이 재발화하는 것 방지).
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                val now = System.currentTimeMillis()
                if (now - lastRun < MIN_INTERVAL_MS) return
                lastRun = now
                handle(allowTrigger = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        }
    }

    override fun onInterrupt() {}

    private fun handle(allowTrigger: Boolean) {
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
                    if (!inTargetRoom || activeRoom != matched) Log.i(TAG, "entered target room: $matched")
                    inTargetRoom = true
                    activeRoom = matched
                }
                title != null -> { // 현재 방을 읽었는데 대상이 아님 → 수집 중단.
                    inTargetRoom = false
                    activeRoom = ""
                }
                // title == null: 방 식별 불가 → 직전 상태 유지(섣불리 해제/입장하지 않음).
            }
            if (inTargetRoom && activeRoom.isNotEmpty()) scrape(allowTrigger)
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

    /**
     * 현재 화면의 말풍선을 수집한다. 동시에 '맨 아래(최신) 새 메시지'를 추적해, allowTrigger면
     * 그 메시지가 멘션 요약 명령인지 판정한다(스크롤/방열기에선 allowTrigger=false라 발화 안 함).
     */
    private fun scrape(allowTrigger: Boolean) {
        val ownName = Settings.ownName
        val nameId = Settings.nameId
        val timeId = Settings.timeId
        val msgId = Settings.msgId
        val screenW = resources.displayMetrics.widthPixels
        val rect = android.graphics.Rect()

        val roots = collectRoots()

        // 화면에서 가장 아래(가장 최신)에 보이는 말풍선 추적: 트리거 판정 대상.
        var bottomY = Int.MIN_VALUE
        var bottomText = ""
        var bottomIsNew = false

        var newCount = 0
        for (root in roots) {
            // 보낸이/시각은 같은 트리 안에서 말풍선보다 먼저 나오므로 윈도우(트리)마다 초기화.
            var curSender = ""
            var curTime = ""
            walk(root) { n ->
                val id = n.viewIdResourceName ?: ""
                // 카톡이 본문/닉네임을 text가 아니라 contentDescription에 두기도 한다 → text 우선, 없으면 cd.
                val value = nodeValue(n)
                if (value.isEmpty()) return@walk
                when {
                    nameId.isNotEmpty() && id == nameId -> curSender = value
                    timeId.isNotEmpty() && id == timeId -> curTime = value
                    msgId.isNotEmpty() && id == msgId -> {
                        // 답장에 인용된 원문은 'Replied/Original message ...'로 와서 중복이므로 건너뜀.
                        if (value.startsWith("Replied message") || value.startsWith("Original message")) {
                            return@walk
                        }
                        // 내 메시지엔 닉네임이 안 뜨고 '우측 정렬'된다(오른쪽 여백 < 왼쪽 여백).
                        // 우측정렬=내 메시지 → 내 닉네임(ownName), 좌측정렬=남 메시지 → 직전 닉네임(curSender).
                        n.getBoundsInScreen(rect)
                        val sender = if ((screenW - rect.right) < rect.left) ownName else curSender
                        // 보낸이를 모르면(내 닉네임 미설정/남 메시지인데 닉네임 화면밖) 건너뜀.
                        if (sender.isNotEmpty()) {
                            val key = DedupeKey.of(activeRoom, sender, value, curTime)
                            val isNew = firstSeen(key)
                            if (isNew) {
                                newCount++
                                Uploader.submit(activeRoom, sender, value, curTime)
                            }
                            if (rect.bottom > bottomY) {
                                bottomY = rect.bottom
                                bottomText = value
                                bottomIsNew = isNew
                            }
                        }
                    }
                }
            }
        }
        if (newCount > 0) Log.i(TAG, "posted $newCount new message(s)")

        // 맨 아래(최신) 메시지가 '새것'이고 멘션 요약 명령이면 발화.
        if (allowTrigger && bottomIsNew && bottomText.isNotEmpty()) {
            maybeTriggerSummary(activeRoom, bottomText)
        }
    }

    /** 노드의 표시값: text 우선, 비었으면 contentDescription. 둘 다 trim. */
    private fun nodeValue(n: AccessibilityNodeInfo): String {
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return n.contentDescription?.toString()?.trim() ?: ""
    }

    /**
     * 명령 메시지면 Modal로 요약을 받아 그 방으로 발신한다. 자동발신이 켜져 있고, 멘션+요약 키워드를
     * 모두 담고 있으며(봇 마커 없음), 그 방에 진행중 요약이 없을 때만.
     */
    private fun maybeTriggerSummary(room: String, command: String) {
        if (!Settings.autoReply) return
        val mention = Settings.effectiveMention()
        if (!SummaryTrigger.isTrigger(command, mention, Settings.summaryKeyword, Settings.botMarker)) return
        if (!summarizing.add(room)) return // 이미 진행중
        Log.i(TAG, "summary trigger room=$room cmd=${command.take(40)}")
        Summarizer.request(room, command) { res ->
            if (res.ok && res.summary.isNotBlank()) {
                mainHandler.post {
                    try {
                        sendToRoom(room, Settings.botMarker + res.summary)
                    } finally {
                        summarizing.remove(room)
                    }
                }
            } else {
                Log.w(TAG, "summary failed room=$room err=${res.error}")
                summarizing.remove(room)
            }
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
