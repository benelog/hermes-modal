package net.benelog.kakaocollector

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** 상태 확인 + 접근성 설정 열기 + 설정 편집(토큰/URL/방/ids) + 연결 테스트 화면. */
class MainActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etUrl: EditText
    private lateinit var etRoom: EditText
    private lateinit var etOwnName: EditText
    private lateinit var etMsgId: EditText
    private lateinit var etNameId: EditText
    private lateinit var etTimeId: EditText
    private lateinit var etTitleId: EditText
    private lateinit var cbCalibrate: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Settings.init(this)

        etToken = findViewById(R.id.etToken)
        etUrl = findViewById(R.id.etUrl)
        etRoom = findViewById(R.id.etRoom)
        etOwnName = findViewById(R.id.etOwnName)
        etMsgId = findViewById(R.id.etMsgId)
        etNameId = findViewById(R.id.etNameId)
        etTimeId = findViewById(R.id.etTimeId)
        etTitleId = findViewById(R.id.etTitleId)
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
            Poster.post(
                JSONObject()
                    .put("room", Settings.roomName)
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
        refreshInfo()
    }

    private fun populateForm() {
        etToken.setText(if (Settings.isTokenSet()) Settings.token else "")
        etUrl.setText(Settings.ingestUrl)
        etRoom.setText(Settings.roomName)
        etOwnName.setText(Settings.ownName)
        etMsgId.setText(Settings.msgId)
        etNameId.setText(Settings.nameId)
        etTimeId.setText(Settings.timeId)
        etTitleId.setText(Settings.titleId)
        cbCalibrate.isChecked = Settings.calibrate
    }

    private fun saveForm() {
        Settings.save(
            token = etToken.text.toString(),
            ingestUrl = etUrl.text.toString(),
            roomName = etRoom.text.toString(),
            ownName = etOwnName.text.toString(),
            msgId = etMsgId.text.toString(),
            nameId = etNameId.text.toString(),
            timeId = etTimeId.text.toString(),
            titleId = etTitleId.text.toString(),
            calibrate = cbCalibrate.isChecked,
        )
        refreshInfo()
    }

    private fun refreshInfo() {
        findViewById<TextView>(R.id.info).text = buildString {
            append("방: ").append(Settings.roomName).append('\n')
            append("엔드포인트: ").append(Settings.ingestUrl).append('\n')
            append("토큰: ").append(if (Settings.isTokenSet()) "설정됨" else "⚠ 미설정").append('\n')
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
