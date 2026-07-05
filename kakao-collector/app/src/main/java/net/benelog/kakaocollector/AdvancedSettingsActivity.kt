package net.benelog.kakaocollector

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 고급 설정: 한 번 맞춰두면 거의 바꿀 일 없는 값들(토큰/URL/내 닉네임/화면 id/키워드/CALIBRATE).
 * 첫 화면([MainActivity])을 간결하게 유지하기 위해 분리했다.
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etUrl: EditText
    private lateinit var etSummarizeUrl: EditText
    private lateinit var etOwnName: EditText
    private lateinit var etMsgId: EditText
    private lateinit var etNameId: EditText
    private lateinit var etTimeId: EditText
    private lateinit var etTitleId: EditText
    private lateinit var etMentionKeyword: EditText
    private lateinit var etSummaryKeyword: EditText
    private lateinit var etInputId: EditText
    private lateinit var etSendId: EditText
    private lateinit var cbCalibrate: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)
        Settings.init(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etToken = findViewById(R.id.etToken)
        etUrl = findViewById(R.id.etUrl)
        etSummarizeUrl = findViewById(R.id.etSummarizeUrl)
        etOwnName = findViewById(R.id.etOwnName)
        etMsgId = findViewById(R.id.etMsgId)
        etNameId = findViewById(R.id.etNameId)
        etTimeId = findViewById(R.id.etTimeId)
        etTitleId = findViewById(R.id.etTitleId)
        etMentionKeyword = findViewById(R.id.etMentionKeyword)
        etSummaryKeyword = findViewById(R.id.etSummaryKeyword)
        etInputId = findViewById(R.id.etInputId)
        etSendId = findViewById(R.id.etSendId)
        cbCalibrate = findViewById(R.id.cbCalibrate)

        populateForm()

        findViewById<Button>(R.id.btnAdvSave).setOnClickListener {
            saveForm()
            Toast.makeText(this, "설정 저장됨", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun populateForm() {
        etToken.setText(if (Settings.isTokenSet()) Settings.token else "")
        etUrl.setText(Settings.ingestUrl)
        etSummarizeUrl.setText(Settings.summarizeUrl)
        etOwnName.setText(Settings.ownName)
        etMsgId.setText(Settings.msgId)
        etNameId.setText(Settings.nameId)
        etTimeId.setText(Settings.timeId)
        etTitleId.setText(Settings.titleId)
        etMentionKeyword.setText(Settings.mentionKeyword)
        etSummaryKeyword.setText(Settings.summaryKeyword)
        etInputId.setText(Settings.inputId)
        etSendId.setText(Settings.sendId)
        cbCalibrate.isChecked = Settings.calibrate
    }

    private fun saveForm() {
        Settings.saveAdvanced(
            token = etToken.text.toString(),
            ingestUrl = etUrl.text.toString(),
            summarizeUrl = etSummarizeUrl.text.toString(),
            ownName = etOwnName.text.toString(),
            msgId = etMsgId.text.toString(),
            nameId = etNameId.text.toString(),
            timeId = etTimeId.text.toString(),
            titleId = etTitleId.text.toString(),
            mentionKeyword = etMentionKeyword.text.toString(),
            summaryKeyword = etSummaryKeyword.text.toString(),
            inputId = etInputId.text.toString(),
            sendId = etSendId.text.toString(),
            calibrate = cbCalibrate.isChecked,
        )
    }
}
