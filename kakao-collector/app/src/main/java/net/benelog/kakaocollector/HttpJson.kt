package net.benelog.kakaocollector

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection 공통 처리(타임아웃, 본문 쓰기/읽기, disconnect). 엔드포인트별 의미
 * 부여는 [ModalApi]가 한다. 네트워크 예외는 삼키지 않고 던진다 — 호출자가 로그/결과에
 * 오류 메시지를 담아야 하기 때문.
 */
object HttpJson {
    private const val CONNECT_TIMEOUT_MS = 10_000

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code == 200
    }

    fun get(url: String, readTimeoutMs: Int): Response = request(url, "GET", null, readTimeoutMs)

    fun post(url: String, body: JSONObject, readTimeoutMs: Int): Response =
        request(url, "POST", body, readTimeoutMs)

    private fun request(url: String, method: String, body: JSONObject?, readTimeoutMs: Int): Response {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn?.disconnect()
        }
    }
}
