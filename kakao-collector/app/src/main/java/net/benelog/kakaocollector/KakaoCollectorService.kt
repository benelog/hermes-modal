package net.benelog.kakaocollector

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 카카오톡 화면을 접근성으로 읽어 대상 방('아카라카북클럽') 메시지를 수집한다.
 *
 * 동작: 대상 방이 화면에 있을 때(사용자가 열어 스크롤하는 동안) 보이는 말풍선을
 * 본문/보낸이/시각으로 골라 중복 제거 후 Modal /ingest 로 POST. 카톡엔 아무것도 보내지 않음.
 * 조용한 방(알림 끔)도 동작한다(화면을 직접 읽으므로).
 */
class KakaoCollectorService : AccessibilityService() {

    companion object {
        const val TAG = "KakaoCollector"
        private const val MIN_INTERVAL_MS = 500L
        private const val SEEN_CAP = 3000
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30일
    }

    private val seen = LinkedHashSet<String>()
    private var lastRun = 0L

    // 카톡은 방 제목 노드를 '방을 열 때'만 트리에 노출하고 스크롤 중엔 빼버린다.
    // 그래서 매번 제목으로 판별하면 스크롤 중 수집이 끊긴다 → 방 입장 여부를 래치로 기억한다.
    private var inTargetRoom = false
    private var activeRoom = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Settings.init(this) // Application에서 이미 했지만 방어적으로(멱등).
        Uploader.init(this, RETENTION_MS)
        // 재시작해도 최근 수집분을 '이미 봄'으로 인식 → 재전송 방지.
        seen.addAll(Uploader.recentKeys(SEEN_CAP))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != Config.KAKAO_PACKAGE) return
        when (event.eventType) {
            // 화면 전환은 방 판별의 기준점이라 레이트리밋 없이 항상 처리.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handle()
            // 스크롤/내용변경은 폭주하므로 레이트리밋.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                val now = System.currentTimeMillis()
                if (now - lastRun < MIN_INTERVAL_MS) return
                lastRun = now
                handle()
            }
        }
    }

    override fun onInterrupt() {}

    private fun handle() {
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
            if (inTargetRoom && activeRoom.isNotEmpty()) scrape()
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

    /**
     * 현재 화면의 말풍선을 수집한다. 카톡은 말풍선 RecyclerView를 rootInActiveWindow가 아닌
     * '다른 윈도우'에 둘 수 있어, getWindows()의 모든 윈도우를 훑는다(없으면 활성 윈도우 폴백).
     */
    private fun scrape() {
        val ownName = Settings.ownName
        val nameId = Settings.nameId
        val timeId = Settings.timeId
        val msgId = Settings.msgId
        val screenW = resources.displayMetrics.widthPixels
        val rect = android.graphics.Rect()

        val roots = ArrayList<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

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
                            if (firstSeen(key)) {
                                newCount++
                                Uploader.submit(activeRoom, sender, value, curTime)
                            }
                        }
                    }
                }
            }
        }
        if (newCount > 0) Log.i(TAG, "posted $newCount new message(s)")
    }

    /** 노드의 표시값: text 우선, 비었으면 contentDescription. 둘 다 trim. */
    private fun nodeValue(n: AccessibilityNodeInfo): String {
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return n.contentDescription?.toString()?.trim() ?: ""
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
