package net.benelog.kakaocollector

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/** 수집 메시지를 로컬 저장(중복제거) 후 '새 것만' Modal로 전송한다. 모든 DB/네트워크 작업은 단일 백그라운드 스레드. */
object Uploader {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var store: MessageStore

    /** 시작 시 1회. store 준비 + 오래된 행 정리(retentionMillis 초과). */
    fun init(context: Context, retentionMillis: Long) {
        if (!::store.isInitialized) store = MessageStore(context.applicationContext)
        val cutoff = System.currentTimeMillis() - retentionMillis
        exec.execute { store.prune(cutoff) }
    }

    /** 인메모리 seen 시드용 — 최근 키. init 이후 호출. */
    fun recentKeys(limit: Int): Set<String> =
        if (::store.isInitialized) store.recentKeys(limit) else emptySet()

    /**
     * 수집 메시지 제출: DB 기록/병합 → 새 행이거나 잘림→완전 갱신이면 POST(완전한 본문) → 성공 시 sent_ok.
     * 잘린 본문이 이미 더 완전한 행으로 있으면(SKIPPED) 아무것도 안 한다. 갱신 시에도 완전한 본문을 보내
     * 서버가 같은 레코드를 제자리(received_at 유지=순서 보존)에서 합치게 한다.
     */
    fun submit(room: String, sender: String, text: String, ts: String) {
        exec.execute {
            try {
                val r = store.recordOrMerge(room, sender, text, ts, System.currentTimeMillis())
                if (r.outcome == MessageStore.Outcome.SKIPPED) return@execute
                val ok = Poster.post(
                    JSONObject().put("room", room).put("sender", sender).put("text", text).put("ts", ts),
                )
                if (ok) store.markSent(r.rowId)
            } catch (e: Exception) {
                // 예외로 executor 스레드가 조용히 죽지 않게(다음 제출은 계속). DB 미기록분은 재시작 후 재시도됨.
                Log.w(KakaoCollectorService.TAG, "uploader submit failed: ${e.message}")
            }
        }
    }

    /** 연결 테스트용: 저장하지 않고 즉시 POST(백그라운드). */
    fun testPost(room: String, sender: String, text: String) {
        exec.execute {
            Poster.post(JSONObject().put("room", room).put("sender", sender).put("text", text).put("ts", ""))
        }
    }
}
