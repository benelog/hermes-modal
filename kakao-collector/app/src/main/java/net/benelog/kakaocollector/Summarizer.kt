package net.benelog.kakaocollector

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Modal `kakao_summarize` 엔드포인트를 호출해 한 방의 최근 대화 요약문을 받아온다.
 * 요약 생성은 Modal에 떠 있는 Hermes LLM(`hermes -z`)이 수행한다. 콜드스타트+LLM 때문에
 * 수십 초~수 분 걸릴 수 있어 readTimeout을 길게 둔다. 모든 호출은 백그라운드 스레드.
 *
 * 서비스(자동 발신)와 액티비티(수동 테스트) 양쪽에서 쓰인다.
 */
object Summarizer {
    private val exec = Executors.newSingleThreadExecutor()

    data class Result(val ok: Boolean, val summary: String, val count: Int, val error: String?)

    /** 백그라운드로 요약 요청 후 [onResult] 콜백(워커 스레드에서 호출). */
    fun request(room: String, command: String, onResult: (Result) -> Unit) {
        exec.execute { onResult(requestBlocking(room, command)) }
    }

    /** 동기 요약 요청. 실패는 예외 없이 Result(ok=false)로 돌려준다. */
    fun requestBlocking(room: String, command: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(Settings.summarizeUrl + "?token=" + URLEncoder.encode(Settings.token, "UTF-8"))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 180_000 // Hermes 콜드스타트 + LLM 요약 대기
            }
            val body = JSONObject().put("room", room).put("command", command)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code != 200) {
                Log.w(KakaoCollectorService.TAG, "summarize http=$code body=${resp.take(300)}")
                return Result(false, "", 0, "http $code")
            }
            val json = JSONObject(resp)
            Result(
                ok = json.optBoolean("ok", false),
                summary = json.optString("summary", ""),
                count = json.optInt("count", 0),
                error = if (json.has("error")) json.optString("error") else null,
            )
        } catch (e: Exception) {
            Log.w(KakaoCollectorService.TAG, "summarize failed: ${e.message}")
            Result(false, "", 0, e.message)
        } finally {
            conn?.disconnect()
        }
    }
}
