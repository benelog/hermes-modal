package net.benelog.kakaocollector

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Modal 서버 엔드포인트 클라이언트: 수집(/ingest)·통계(/stats)·요약(/summarize).
 * URL/토큰은 [Settings]에서 읽고, 전송 자체는 [HttpJson]에 맡긴다. 모든 함수는 블로킹이므로
 * 백그라운드 스레드에서 부른다(요약만 자체 executor의 비동기 래퍼 [requestSummary] 제공).
 */
object ModalApi {
    // Modal 콜드스타트가 15초를 넘길 수 있음(전송 실패 누적의 한 원인) → 30초.
    private const val READ_TIMEOUT_MS = 30_000
    // Hermes 콜드스타트 + LLM 요약 대기.
    private const val SUMMARY_READ_TIMEOUT_MS = 180_000

    private val summaryExec = Executors.newSingleThreadExecutor()

    data class SummaryResult(val ok: Boolean, val summary: String, val count: Int, val error: String?)

    private fun withToken(base: String): String =
        base + "?token=" + URLEncoder.encode(Settings.token, "UTF-8")

    /** 수집 메시지 1건 전송. HTTP 200이면 true. */
    fun ingest(record: MessageRecord): Boolean = try {
        val res = HttpJson.post(withToken(Settings.ingestUrl), record.toJson(), READ_TIMEOUT_MS)
        if (!res.ok) Log.w(KakaoCollectorService.TAG, "ingest http=${res.code}")
        res.ok
    } catch (e: Exception) {
        Log.w(KakaoCollectorService.TAG, "ingest failed: ${e.message}")
        false
    }

    /** 서버에 저장된 발신일별 건수(전송 검증용). 실패/ok=false면 null. */
    fun fetchDailyCounts(room: String, start: String, end: String): Map<String, Int>? = try {
        val url = withToken(Settings.statsUrl) +
            "&room=" + URLEncoder.encode(room, "UTF-8") +
            "&start=" + start + "&end=" + end
        val res = HttpJson.get(url, READ_TIMEOUT_MS)
        if (!res.ok) {
            Log.w(KakaoCollectorService.TAG, "stats http=${res.code}")
            null
        } else {
            val json = JSONObject(res.body)
            if (!json.optBoolean("ok")) {
                null
            } else {
                val counts = json.optJSONObject("counts") ?: JSONObject()
                val out = HashMap<String, Int>()
                for (k in counts.keys()) out[k] = counts.getInt(k)
                out
            }
        }
    } catch (e: Exception) {
        Log.w(KakaoCollectorService.TAG, "stats failed: ${e.message}")
        null
    }

    /** 요약 요청(비동기). [onResult]는 백그라운드 스레드에서 불린다. */
    fun requestSummary(room: String, command: String, onResult: (SummaryResult) -> Unit) {
        summaryExec.execute { onResult(requestSummaryBlocking(room, command)) }
    }

    fun requestSummaryBlocking(room: String, command: String): SummaryResult = try {
        val body = JSONObject().put("room", room).put("command", command)
        val res = HttpJson.post(withToken(Settings.summarizeUrl), body, SUMMARY_READ_TIMEOUT_MS)
        if (!res.ok) {
            Log.w(KakaoCollectorService.TAG, "summarize http=${res.code} body=${res.body.take(300)}")
            SummaryResult(false, "", 0, "http ${res.code}")
        } else {
            val json = JSONObject(res.body)
            SummaryResult(
                ok = json.optBoolean("ok", false),
                summary = json.optString("summary", ""),
                count = json.optInt("count", 0),
                error = if (json.has("error")) json.optString("error") else null,
            )
        }
    } catch (e: Exception) {
        Log.w(KakaoCollectorService.TAG, "summarize failed: ${e.message}")
        SummaryResult(false, "", 0, e.message)
    }
}
