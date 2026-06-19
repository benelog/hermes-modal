package net.benelog.kakaocollector

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** 상태 확인 + 접근성 설정 열기 + 연결 테스트만 하는 가벼운 화면. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.info).text = buildString {
            append("방: ").append(Config.ROOM_NAME).append('\n')
            append("엔드포인트: ").append(Config.INGEST_URL).append('\n')
            append("토큰: ").append(if (Config.TOKEN.startsWith("PUT_")) "⚠ 미설정" else "설정됨").append('\n')
            append("CALIBRATE: ").append(Config.CALIBRATE).append('\n')
            append("ids: msg=").append(Config.MSG_ID).append('\n')
            append("     name=").append(Config.NAME_ID).append('\n')
            append("     time=").append(Config.TIME_ID)
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            Poster.post(
                JSONObject()
                    .put("room", Config.ROOM_NAME)
                    .put("sender", "앱테스트")
                    .put("text", "전용앱 연결 테스트")
                    .put("ts", ""),
            )
            findViewById<TextView>(R.id.status).text =
                "테스트 메시지 전송함 → Modal /messages 로 확인하세요."
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.status).text =
            if (isServiceEnabled()) "접근성 서비스: 켜짐 ✅" else "접근성 서비스: 꺼짐 — 아래 버튼으로 켜세요"
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${KakaoCollectorService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
