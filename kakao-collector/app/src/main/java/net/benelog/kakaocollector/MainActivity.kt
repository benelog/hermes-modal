package net.benelog.kakaocollector

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 첫 화면: 상태 확인 + 접근성 설정 열기 + 자주 바꾸는 설정(대상 방/자동발신) + 연결·요약 테스트
 * + 백필 수집 실행(방/기간 지정 → 카카오톡 자동 스크롤 수집, [BackfillController]).
 * 토큰/URL/화면 id/키워드 같은 한 번 맞추면 끝인 값은 [AdvancedSettingsActivity]로 분리.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val STATUS_POLL_MS = 700L
    }

    private lateinit var etRoom: EditText
    private lateinit var cbAutoReply: CheckBox
    private lateinit var spBackfillRoom: Spinner

    private val mainHandler = Handler(Looper.getMainLooper())

    // 백필 기간(기본: 최근 하루). 프리셋/픽커가 갱신.
    private val fromCal: Calendar = Calendar.getInstance()
    private val toCal: Calendar = Calendar.getInstance()
    private val rangeFmt = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)

    // 백필 진행 상태 폴링(화면에 보이는 동안만).
    private val backfillStatusTick = object : Runnable {
        override fun run() {
            renderBackfillStatus()
            mainHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Settings.init(this)

        etRoom = findViewById(R.id.etRoom)
        cbAutoReply = findViewById(R.id.cbAutoReply)
        spBackfillRoom = findViewById(R.id.spBackfillRoom)

        populateForm()
        setBackfillPreset(1)

        findViewById<Button>(R.id.btnBackfillFrom).setOnClickListener { pickDateTime(fromCal) }
        findViewById<Button>(R.id.btnBackfillTo).setOnClickListener { pickDateTime(toCal) }
        findViewById<Button>(R.id.btnPreset1d).setOnClickListener { setBackfillPreset(1) }
        findViewById<Button>(R.id.btnPreset3d).setOnClickListener { setBackfillPreset(3) }
        findViewById<Button>(R.id.btnPreset7d).setOnClickListener { setBackfillPreset(7) }
        findViewById<Button>(R.id.btnBackfillStart).setOnClickListener { startBackfill() }
        findViewById<Button>(R.id.btnBackfillStop).setOnClickListener {
            BackfillController.stop()
            renderBackfillStatus()
        }

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
        refreshBackfillRooms()
        mainHandler.post(backfillStatusTick)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(backfillStatusTick)
    }

    // ── 백필 수집 ────────────────────────────────────────────────

    /** 스피너에 대상 방 목록 채우기(설정 화면에서 바꾸고 돌아와도 최신으로). */
    private fun refreshBackfillRooms() {
        val rooms = Settings.roomNamesList()
        val selectedBefore = spBackfillRoom.selectedItem as? String
        spBackfillRoom.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, rooms,
        )
        val keep = rooms.indexOf(selectedBefore)
        if (keep >= 0) spBackfillRoom.setSelection(keep)
    }

    /** 원클릭 프리셋: 지금부터 N일 전 ~ 지금. */
    private fun setBackfillPreset(days: Int) {
        toCal.timeInMillis = System.currentTimeMillis()
        fromCal.timeInMillis = toCal.timeInMillis - days * 24L * 60 * 60 * 1000
        renderBackfillRange()
    }

    /** 날짜 → 시간 순으로 고르는 픽커. 고른 뒤 버튼 라벨 갱신. */
    private fun pickDateTime(cal: Calendar) {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                TimePickerDialog(
                    this,
                    { _, hh, mm ->
                        cal.set(Calendar.HOUR_OF_DAY, hh)
                        cal.set(Calendar.MINUTE, mm)
                        renderBackfillRange()
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true,
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun renderBackfillRange() {
        findViewById<Button>(R.id.btnBackfillFrom).text = "시작 ${rangeFmt.format(fromCal.time)}"
        findViewById<Button>(R.id.btnBackfillTo).text = "끝 ${rangeFmt.format(toCal.time)}"
    }

    private fun startBackfill() {
        saveForm() // 화면의 방 목록으로 바로 수집되도록 먼저 저장.
        refreshBackfillRooms()
        val room = spBackfillRoom.selectedItem as? String
        if (room.isNullOrBlank()) {
            Toast.makeText(this, "대상 방을 먼저 설정하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isServiceEnabled()) {
            Toast.makeText(this, "접근성 서비스가 꺼져 있습니다 — 켠 뒤 다시 시작하세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (KakaoCollectorService.instance == null) {
            // 토글은 켜져 있는데 아직 바인딩 전(방금 켠 직후 등) — 재시도 안내.
            Toast.makeText(this, "접근성 서비스 연결 대기 중 — 잠시 후 다시 눌러주세요", Toast.LENGTH_LONG).show()
            return
        }
        if (fromCal.timeInMillis >= toCal.timeInMillis) {
            Toast.makeText(this, "시작 시점이 끝 시점보다 앞서야 합니다", Toast.LENGTH_SHORT).show()
            return
        }
        val err = BackfillController.start(
            BackfillController.Request(room, fromCal.timeInMillis, toCal.timeInMillis),
        )
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            return
        }
        renderBackfillStatus()
        // 카카오톡을 앞으로 — 이후 방 진입/스크롤은 접근성 서비스가 이어받는다.
        val kakao = packageManager.getLaunchIntentForPackage(Config.KAKAO_PACKAGE)
        if (kakao != null) {
            startActivity(kakao)
        } else {
            Toast.makeText(this, "카카오톡 앱을 찾을 수 없습니다", Toast.LENGTH_LONG).show()
            BackfillController.stop()
        }
    }

    private fun renderBackfillStatus() {
        val s = BackfillController.statusNow()
        val phaseLabel = when (s.phase) {
            BackfillController.Phase.IDLE -> "대기"
            BackfillController.Phase.NAVIGATE -> "방 여는 중"
            BackfillController.Phase.ARMED -> "수동 열기 대기"
            BackfillController.Phase.SEEK -> "과거로 이동 중"
            BackfillController.Phase.COLLECT -> "수집 중"
            BackfillController.Phase.DONE -> "완료"
            BackfillController.Phase.FAILED -> "실패"
        }
        findViewById<TextView>(R.id.backfillStatus).text = buildString {
            append("백필: ").append(phaseLabel)
            if (s.room.isNotEmpty()) append(" · ").append(s.room)
            append('\n').append(s.message)
        }
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
