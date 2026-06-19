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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != Config.KAKAO_PACKAGE) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> maybeScrape()
        }
    }

    override fun onInterrupt() {}

    private fun maybeScrape() {
        val now = System.currentTimeMillis()
        if (now - lastRun < MIN_INTERVAL_MS) return
        lastRun = now
        val root = rootInActiveWindow ?: return
        try {
            if (Config.CALIBRATE) {
                dumpTree(root, 0)
                return
            }
            if (!isTargetRoom(root)) return
            scrape(root)
        } catch (e: Exception) {
            Log.w(TAG, "scrape error: ${e.message}")
        }
    }

    private fun isTargetRoom(root: AccessibilityNodeInfo): Boolean {
        if (Config.TITLE_ID.isNotEmpty()) {
            for (t in root.findAccessibilityNodeInfosByViewId(Config.TITLE_ID)) {
                val txt = t.text?.toString() ?: continue
                if (txt.contains(Config.ROOM_NAME)) return true
            }
        }
        // 제목 노드를 못 찾으면 화면 어딘가에 방 이름 텍스트가 있는지로 보조 판별.
        var found = false
        walk(root) { n ->
            if (!found && n.text?.toString() == Config.ROOM_NAME) found = true
        }
        return found
    }

    private fun scrape(root: AccessibilityNodeInfo) {
        val ordered = ArrayList<AccessibilityNodeInfo>()
        walk(root) { ordered.add(it) }

        var curSender = ""
        var curTime = ""
        var newCount = 0
        for (n in ordered) {
            val id = n.viewIdResourceName ?: ""
            val txt = n.text?.toString()?.trim() ?: ""
            if (txt.isEmpty()) continue
            when {
                Config.NAME_ID.isNotEmpty() && id == Config.NAME_ID -> curSender = txt
                Config.TIME_ID.isNotEmpty() && id == Config.TIME_ID -> curTime = txt
                Config.MSG_ID.isNotEmpty() && id == Config.MSG_ID -> {
                    // 서버 message_key와 동일하게 \u0001 구분자로 필드 경계를 명확히 한다.
                    val key = curSender + "\u0001" + txt + "\u0001" + curTime
                    if (remember(key)) {
                        newCount++
                        Poster.post(
                            JSONObject()
                                .put("room", Config.ROOM_NAME)
                                .put("sender", curSender)
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

    /** 캘리브레이션용: 현재 화면의 id/text를 Logcat에 덤프. */
    private fun dumpTree(node: AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        val id = node.viewIdResourceName
        val txt = node.text?.toString()
        if ((id != null && id.startsWith("com.kakao")) || (!txt.isNullOrEmpty())) {
            Log.i(TAG, " ".repeat(depth) + "id=" + id + " text=" + (txt ?: ""))
        }
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1)
        }
    }
}
