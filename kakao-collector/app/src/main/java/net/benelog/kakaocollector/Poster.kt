package net.benelog.kakaocollector

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** 수집한 메시지 1건을 Modal /ingest 로 HTTPS POST 한다(백그라운드 스레드). */
object Poster {
    private val exec = Executors.newSingleThreadExecutor()

    fun post(rec: JSONObject) {
        exec.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(
                    Settings.ingestUrl + "?token=" + URLEncoder.encode(Settings.token, "UTF-8"),
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }
                conn.outputStream.use { it.write(rec.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    Log.w(KakaoCollectorService.TAG, "ingest http=$code")
                }
            } catch (e: Exception) {
                Log.w(KakaoCollectorService.TAG, "post failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }
}
