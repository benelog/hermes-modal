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

/** 상태 확인 + 접근성 설정 열기 + 설정 편집(토큰/URL/방/ids/요약발신) + 연결·요약 테스트 화면. */
class MainActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etUrl: EditText
    private lateinit var etSummarizeUrl: EditText
    private lateinit var etRoom: EditText
    private lateinit var etOwnName: EditText
    private lateinit var etMsgId: EditText
    private lateinit var etNameId: EditText
    private lateinit var etTimeId: EditText
    private lateinit var etTitleId: EditText
    private lateinit var etMentionKeyword: EditText
    private lateinit var etSummaryKeyword: EditText
    private lateinit var etInputId: EditText
    private lateinit var etSendId: EditText
    private lateinit var cbAutoReply: CheckBox
    private lateinit var cbCalibrate: CheckBox

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Settings.init(this)

        etToken = findViewById(R.id.etToken)
        etUrl = findViewById(R.id.etUrl)
        etSummarizeUrl = findViewById(R.id.etSummarizeUrl)
        etRoom = findViewById(R.id.etRoom)
        etOwnName = findViewById(R.id.etOwnName)
        etMsgId = findViewById(R.id.etMsgId)
        etNameId = findViewById(R.id.etNameId)
        etTimeId = findViewById(R.id.etTimeId)
        etTitleId = findViewById(R.id.etTitleId)
        etMentionKeyword = findViewById(R.id.etMentionKeyword)
        etSummaryKeyword = findViewById(R.id.etSummaryKeyword)
        etInputId = findViewById(R.id.etInputId)
        etSendId = findViewById(R.id.etSendId)
        cbAutoReply = findViewById(R.id.cbAutoReply)
        cbCalibrate = findViewById(R.id.cbCalibrate)

        populateForm()

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
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
        refreshInfo()
    }

    private fun populateForm() {
        etToken.setText(if (Settings.isTokenSet()) Settings.token else "")
        etUrl.setText(Settings.ingestUrl)
        etSummarizeUrl.setText(Settings.summarizeUrl)
        etRoom.setText(Settings.roomNamesRaw)
        etOwnName.setText(Settings.ownName)
        etMsgId.setText(Settings.msgId)
        etNameId.setText(Settings.nameId)
        etTimeId.setText(Settings.timeId)
        etTitleId.setText(Settings.titleId)
        etMentionKeyword.setText(Settings.mentionKeyword)
        etSummaryKeyword.setText(Settings.summaryKeyword)
        etInputId.setText(Settings.inputId)
        etSendId.setText(Settings.sendId)
        cbAutoReply.isChecked = Settings.autoReply
        cbCalibrate.isChecked = Settings.calibrate
    }

    private fun saveForm() {
        Settings.save(
            token = etToken.text.toString(),
            ingestUrl = etUrl.text.toString(),
            summarizeUrl = etSummarizeUrl.text.toString(),
            roomNames = etRoom.text.toString(),
            ownName = etOwnName.text.toString(),
            msgId = etMsgId.text.toString(),
            nameId = etNameId.text.toString(),
            timeId = etTimeId.text.toString(),
            titleId = etTitleId.text.toString(),
            mentionKeyword = etMentionKeyword.text.toString(),
            summaryKeyword = etSummaryKeyword.text.toString(),
            inputId = etInputId.text.toString(),
            sendId = etSendId.text.toString(),
            autoReply = cbAutoReply.isChecked,
            calibrate = cbCalibrate.isChecked,
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
