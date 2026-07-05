package net.benelog.kakaocollector

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 첫 화면: 상태 확인 + 접근성 설정 열기 + 자주 바꾸는 설정(대상 방/자동발신) + 연결·요약 테스트.
 * 토큰/URL/화면 id/키워드 같은 한 번 맞추면 끝인 값은 [AdvancedSettingsActivity]로 분리.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etRoom: EditText
    private lateinit var cbAutoReply: CheckBox

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Settings.init(this)

        etRoom = findViewById(R.id.etRoom)
        cbAutoReply = findViewById(R.id.cbAutoReply)

        populateForm()

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAdvanced).setOnClickListener {
            saveForm() // 첫 화면에서 고치던 값이 이동 중에 날아가지 않게 먼저 저장.
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveForm()
            Toast.makeText(this, "설정 저장됨", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            saveForm() // 화면에 입력한 값으로 바로 테스트되도록 먼저 저장.
            Uploader.testPost(Settings.firstRoom(), "앱테스트", "전용앱 연결 테스트")
            findViewById<TextView>(R.id.status).text =
                "테스트 메시지 전송함 → Modal /messages 로 확인하세요."
        }
        findViewById<Button>(R.id.btnSummaryTest).setOnClickListener {
            saveForm()
            val room = Settings.firstRoom()
            findViewById<TextView>(R.id.status).text = "요약 생성 중… (Hermes 콜드스타트로 수십 초 걸릴 수 있음)"
            // 발신 경로와 분리: 결과를 방에 보내지 않고 앱에 표시만 한다(Modal/Hermes 경로 검증).
            Summarizer.request(room, "요약") { res ->
                mainHandler.post {
                    findViewById<TextView>(R.id.status).text = if (res.ok && res.summary.isNotBlank()) {
                        "요약(테스트, $room · ${res.count}건):\n\n${res.summary}"
                    } else {
                        "요약 실패: ${res.error ?: "응답 없음"}"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.status).text =
            if (isServiceEnabled()) "접근성 서비스: 켜짐 ✅" else "접근성 서비스: 꺼짐 — 아래 버튼으로 켜세요"
        refreshInfo() // 고급 설정에서 돌아올 때도 최신 값이 보이게.
    }

    private fun populateForm() {
        etRoom.setText(Settings.roomNamesRaw)
        cbAutoReply.isChecked = Settings.autoReply
    }

    private fun saveForm() {
        Settings.saveMain(
            roomNames = etRoom.text.toString(),
            autoReply = cbAutoReply.isChecked,
        )
        refreshInfo()
    }

    private fun refreshInfo() {
        findViewById<TextView>(R.id.info).text = buildString {
            append("대상 방: ").append(Settings.roomNamesList().joinToString(", ")).append('\n')
            append("수집: ").append(Settings.ingestUrl).append('\n')
            append("요약: ").append(Settings.summarizeUrl).append('\n')
            append("토큰: ").append(if (Settings.isTokenSet()) "설정됨" else "⚠ 미설정").append('\n')
            append("자동발신: ").append(if (Settings.autoReply) "ON" else "OFF")
            append(" (멘션='").append(Settings.effectiveMention()).append("' + '")
            append(Settings.summaryKeyword).append("')\n")
            append("발신 id: input=").append(Settings.inputId).append('\n')
            append("         send=").append(Settings.sendId).append('\n')
            append("CALIBRATE: ").append(Settings.calibrate).append('\n')
            append("ids: msg=").append(Settings.msgId).append('\n')
            append("     name=").append(Settings.nameId).append('\n')
            append("     time=").append(Settings.timeId)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${KakaoCollectorService::class.java.name}"
        val enabled = AndroidSettings.Secure.getString(
            contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
