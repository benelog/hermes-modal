package net.benelog.kakaocollector

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 메시지 1건을 Modal /ingest 로 동기 POST. 200이면 true. (Uploader의 백그라운드 스레드에서 호출.) */
object Poster {
    /** 동기 GET → JSON. 실패(비200/파싱 불가)면 null. (백그라운드 스레드에서 호출.) */
    fun getJson(urlString: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 30_000 // Modal 콜드스타트 고려(post와 동일)
            }
            if (conn.responseCode != 200) {
                Log.w(KakaoCollectorService.TAG, "getJson http=${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (e: Exception) {
            Log.w(KakaoCollectorService.TAG, "getJson failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun post(rec: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(Settings.ingestUrl + "?token=" + URLEncoder.encode(Settings.token, "UTF-8"))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 30_000 // Modal 콜드스타트가 15초를 넘길 수 있음(전송 실패 누적의 한 원인)
            }
            conn.outputStream.use { it.write(rec.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) Log.w(KakaoCollectorService.TAG, "ingest http=$code")
            code == 200
        } catch (e: Exception) {
            Log.w(KakaoCollectorService.TAG, "post failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
