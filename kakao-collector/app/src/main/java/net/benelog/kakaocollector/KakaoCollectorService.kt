package net.benelog.kakaocollector

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

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
    }

    private val seen = LinkedHashSet<String>()
    private var lastRun = 0L

    // 카톡은 방 제목 노드를 '방을 열 때'만 트리에 노출하고 스크롤 중엔 빼버린다.
    // 그래서 매번 제목으로 판별하면 스크롤 중 수집이 끊긴다 → 방 입장 여부를 래치로 기억한다.
    private var inTargetRoom = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Settings.init(this) // Application에서 이미 했지만 방어적으로(멱등).
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != Config.KAKAO_PACKAGE) return
        when (event.eventType) {
            // 화면 전환은 빈도가 낮고 방 판별의 기준점이므로 레이트리밋 없이 항상 처리.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                handle(windowChanged = true)
            // 스크롤/내용변경은 폭주하므로 레이트리밋.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                val now = System.currentTimeMillis()
                if (now - lastRun < MIN_INTERVAL_MS) return
                lastRun = now
                handle(windowChanged = false)
            }
        }
    }

    override fun onInterrupt() {}

    private fun handle(windowChanged: Boolean) {
        val root = rootInActiveWindow ?: return
        try {
            if (Settings.calibrate) {
                dumpTree(root, 0)
                return
            }
            // 방 제목이 보이면 입장 확정(latch ON). 스크롤 중엔 제목이 없어도 유지.
            // 화면이 바뀌었는데(WINDOW_STATE_CHANGED) 제목이 안 보이면 방을 떠난 것 → latch OFF.
            // 래치 갱신은 화면전환 또는 아직 미입장일 때만(스크롤마다 재탐색하면 비싸고, 방 안에선 제목이 안 보임).
            if (windowChanged || !inTargetRoom) {
                val title = visibleRoomTitle(root) // 제목 노드(id/name)가 보이면 그 텍스트, 없으면 null
                when {
                    // 대상 방 제목이 보임 → 입장 확정.
                    title != null && title.contains(Settings.roomName) -> {
                        if (!inTargetRoom) Log.i(TAG, "entered target room")
                        inTargetRoom = true
                    }
                    // '다른 방' 제목이 보임 → 방을 떠난 것.
                    title != null -> inTargetRoom = false
                    // 제목 노드가 아예 안 보임(반응팝업/IME 등 일시 윈도우): 래치 유지.
                    //   단, 아직 미입장 상태라면 방이름 텍스트 폭넓게 한 번 더 탐색(첫 진입 보조).
                    !inTargetRoom && roomNameVisible(root) -> {
                        Log.i(TAG, "entered target room")
                        inTargetRoom = true
                    }
                }
            }
            if (inTargetRoom) scrape(root)
        } catch (e: Exception) {
            Log.w(TAG, "scrape error: ${e.message}")
        }
    }

    /**
     * 화면에 보이는 '방 제목 노드(titleId=id/name)'의 텍스트. 없으면 null.
     * 제목 노드가 보일 때만 방 식별이 확실하므로, 래치를 끄는(다른 방) 판정에 쓴다.
     */
    private fun visibleRoomTitle(activeRoot: AccessibilityNodeInfo): String? {
        val titleId = Settings.titleId
        if (titleId.isEmpty()) return null
        firstTitle(activeRoot, titleId)?.let { return it }
        val wins = windows ?: return null
        for (w in wins) {
            val r = w.root ?: continue
            if (r != activeRoot) firstTitle(r, titleId)?.let { return it }
        }
        return null
    }

    private fun firstTitle(root: AccessibilityNodeInfo, titleId: String): String? {
        for (t in root.findAccessibilityNodeInfosByViewId(titleId)) {
            val txt = t.text?.toString()
            if (!txt.isNullOrEmpty()) return txt
        }
        return null
    }

    private fun roomNameVisible(activeRoot: AccessibilityNodeInfo): Boolean {
        // 방 제목은 보통 활성 윈도우(방 열 때)에 있다 — 여기부터 확인.
        if (treeContainsRoomName(activeRoot)) return true
        // 보조: 접근성으로 접근 가능한 다른 윈도우들도 스캔(있을 때만).
        val wins = windows ?: return false
        for (w in wins) {
            val r = w.root ?: continue
            if (r != activeRoot && treeContainsRoomName(r)) return true
        }
        return false
    }

    private fun treeContainsRoomName(root: AccessibilityNodeInfo): Boolean {
        val roomName = Settings.roomName
        val titleId = Settings.titleId
        if (titleId.isNotEmpty()) {
            for (t in root.findAccessibilityNodeInfosByViewId(titleId)) {
                val txt = t.text?.toString() ?: continue
                if (txt.contains(roomName)) return true
            }
        }
        // 제목 노드 id가 안 맞아도, 트리 어딘가에 방 이름 텍스트가 있으면 인정.
        var found = false
        walk(root) { n ->
            if (!found && n.text?.toString() == roomName) found = true
        }
        return found
    }

    private fun scrape(root: AccessibilityNodeInfo) {
        val roomName = Settings.roomName
        val ownName = Settings.ownName
        val nameId = Settings.nameId
        val timeId = Settings.timeId
        val msgId = Settings.msgId
        val screenW = resources.displayMetrics.widthPixels

        val ordered = ArrayList<AccessibilityNodeInfo>()
        walk(root) { ordered.add(it) }

        var curSender = ""
        var curTime = ""
        var newCount = 0
        val rect = android.graphics.Rect()
        for (n in ordered) {
            val id = n.viewIdResourceName ?: ""
            val txt = n.text?.toString()?.trim() ?: ""
            if (txt.isEmpty()) continue
            when {
                nameId.isNotEmpty() && id == nameId -> curSender = txt
                timeId.isNotEmpty() && id == timeId -> curTime = txt
                msgId.isNotEmpty() && id == msgId -> {
                    // 내 메시지엔 닉네임이 안 뜨고 '우측 정렬'된다(오른쪽 여백 < 왼쪽 여백).
                    // 우측정렬=내 메시지 → 내 닉네임(ownName), 좌측정렬=남 메시지 → 직전 닉네임(curSender).
                    n.getBoundsInScreen(rect)
                    val sender = if ((screenW - rect.right) < rect.left) ownName else curSender
                    // 보낸이를 모르면(내 닉네임 미설정/남 메시지인데 닉네임 화면밖) 건너뜀.
                    if (sender.isEmpty()) continue
                    // 서버 message_key와 동일하게 \u0001 구분자로 필드 경계를 명확히 한다.
                    val key = sender + "\u0001" + txt + "\u0001" + curTime
                    if (remember(key)) {
                        newCount++
                        Poster.post(
                            JSONObject()
                                .put("room", roomName)
                                .put("sender", sender)
                                .put("text", txt)
                                .put("ts", curTime),
                        )
                    }
                }
            }
        }
        if (newCount > 0) Log.i(TAG, "posted $newCount new message(s)")
    }

    /** 처음 보는 key면 기억하고 true. 용량 상한 초과 시 가장 오래된 것부터 제거. */
    private fun remember(key: String): Boolean {
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
        if ((id != null && id.startsWith("com.kakao")) || (!txt.isNullOrEmpty())) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            Log.i(TAG, " ".repeat(depth) + "id=" + id + " L=" + r.left + " R=" + r.right + " text=" + (txt ?: ""))
        }
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1)
        }
    }
}
