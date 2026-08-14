package net.benelog.kakaocollector

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 앱에서 편집 가능한 런타임 설정. SharedPreferences에 저장하므로 토큰/URL 같은 실값을
 * 코드(=git)에 두지 않아도 된다. 한 번도 저장한 적 없는 키는 [Config]의 기본값으로 폴백하되,
 * 저장된 적 있으면 그 값(빈 문자열 포함 = 의도적으로 비활성화한 id)을 그대로 쓴다.
 *
 * [Application][CollectorApp]에서 [init]을 호출해 두면 어디서든 바로 읽을 수 있다.
 */
object Settings {
    private const val PREFS_NAME = "kakao_collector"

    private const val KEY_TOKEN = "token"
    private const val KEY_INGEST_URL = "ingest_url"
    private const val KEY_SUMMARIZE_URL = "summarize_url"
    private const val KEY_STATS_URL = "stats_url"
    private const val KEY_ROOM_NAME = "room_name"
    private const val KEY_ROOM_NAMES = "room_names"
    private const val KEY_OWN_NAME = "own_name"
    private const val KEY_MSG_ID = "msg_id"
    private const val KEY_NAME_ID = "name_id"
    private const val KEY_TIME_ID = "time_id"
    private const val KEY_DATE_INDICATOR_ID = "date_indicator_id"
    private const val KEY_TITLE_ID = "title_id"
    private const val KEY_MENTION_KEYWORD = "mention_keyword"
    private const val KEY_SUMMARY_KEYWORD = "summary_keyword"
    private const val KEY_INPUT_ID = "input_id"
    private const val KEY_SEND_ID = "send_id"
    private const val KEY_AUTO_REPLY = "auto_reply"
    private const val KEY_CALIBRATE = "calibrate"

    private lateinit var prefs: SharedPreferences

    /** 멱등. Application/Activity/Service 어느 쪽에서 먼저 불려도 안전하다. */
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun get(key: String, default: String): String =
        if (prefs.contains(key)) prefs.getString(key, default) ?: default else default

    /** "저장된 값 우선, 없으면 기본값" 규칙을 공유하는 문자열 설정 프로퍼티. */
    private fun stringPref(key: String, default: String): ReadWriteProperty<Any?, String> =
        object : ReadWriteProperty<Any?, String> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): String = get(key, default)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                prefs.edit().putString(key, value).apply()
            }
        }

    private fun booleanPref(key: String, default: Boolean): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
                if (prefs.contains(key)) prefs.getBoolean(key, default) else default

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                prefs.edit().putBoolean(key, value).apply()
            }
        }

    var token: String by stringPref(KEY_TOKEN, Config.TOKEN)
    var ingestUrl: String by stringPref(KEY_INGEST_URL, Config.INGEST_URL)
    var summarizeUrl: String by stringPref(KEY_SUMMARIZE_URL, Config.SUMMARIZE_URL)

    /** 전송 검증용 통계 엔드포인트(발신일별 건수). */
    var statsUrl: String by stringPref(KEY_STATS_URL, Config.STATS_URL)

    /** 내 카톡 닉네임. 내 메시지엔 닉네임이 안 떠서, 보낸이로 채울 값. */
    var ownName: String by stringPref(KEY_OWN_NAME, Config.OWN_NAME)

    var msgId: String by stringPref(KEY_MSG_ID, Config.MSG_ID)
    var nameId: String by stringPref(KEY_NAME_ID, Config.NAME_ID)
    var timeId: String by stringPref(KEY_TIME_ID, Config.TIME_ID)

    /** 날짜 구분 노드 id(메시지에 일자 결합용). 분 시각 미노출의 대체 수단. */
    var dateIndicatorId: String by stringPref(KEY_DATE_INDICATOR_ID, Config.DATE_INDICATOR_ID)

    var titleId: String by stringPref(KEY_TITLE_ID, Config.TITLE_ID)

    /** 멘션 키워드 raw(비어도 됨). 트리거 판정엔 [effectiveMention] 사용. */
    var mentionKeyword: String by stringPref(KEY_MENTION_KEYWORD, Config.MENTION_KEYWORD)

    var summaryKeyword: String by stringPref(KEY_SUMMARY_KEYWORD, Config.SUMMARY_KEYWORD)

    /** 카톡 입력창 EditText id. */
    var inputId: String by stringPref(KEY_INPUT_ID, Config.INPUT_ID)

    /** 카톡 전송 버튼 id. */
    var sendId: String by stringPref(KEY_SEND_ID, Config.SEND_ID)

    /** 방 멘션 요약 감지 시 그 방으로 자동 발신할지. 기본 false(캘리브레이션 후 ON). */
    var autoReply: Boolean by booleanPref(KEY_AUTO_REPLY, Config.AUTO_REPLY)

    var calibrate: Boolean by booleanPref(KEY_CALIBRATE, Config.CALIBRATE)

    /** 대상 방 목록 raw 문자열(줄바꿈 구분). 미저장이면 기존 단일 room_name으로 폴백. */
    var roomNamesRaw: String
        get() = if (prefs.contains(KEY_ROOM_NAMES)) {
            prefs.getString(KEY_ROOM_NAMES, "") ?: ""
        } else {
            get(KEY_ROOM_NAME, Config.ROOM_NAME)
        }
        set(v) = prefs.edit().putString(KEY_ROOM_NAMES, v).apply()

    /** 대상 방 제목 목록. */
    fun roomNamesList(): List<String> = RoomMatch.parse(roomNamesRaw)

    /** 테스트 전송·표시에 쓸 대표(첫) 방. */
    fun firstRoom(): String = roomNamesList().firstOrNull() ?: Config.ROOM_NAME

    /** 실제 트리거에 쓰는 멘션 키워드: 비어 있으면 내 닉네임(ownName)으로 폴백. */
    fun effectiveMention(): String = mentionKeyword.ifBlank { ownName }

    /** 봇 발신 마커(루프 방지용 접두). */
    val botMarker: String get() = Config.BOT_MARKER

    /** 첫 화면 폼 저장(자주 바꾸는 값만, 단일 트랜잭션). */
    fun saveMain(roomNames: String, autoReply: Boolean) {
        prefs.edit()
            .putString(KEY_ROOM_NAMES, roomNames.trim())
            .putBoolean(KEY_AUTO_REPLY, autoReply)
            .apply()
    }

    /** 고급 설정 폼 저장(드물게 바꾸는 값, 단일 트랜잭션). 문자열은 앞뒤 공백 제거. */
    fun saveAdvanced(
        token: String,
        ingestUrl: String,
        summarizeUrl: String,
        statsUrl: String,
        ownName: String,
        msgId: String,
        nameId: String,
        timeId: String,
        titleId: String,
        mentionKeyword: String,
        summaryKeyword: String,
        inputId: String,
        sendId: String,
        calibrate: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_INGEST_URL, ingestUrl.trim())
            .putString(KEY_SUMMARIZE_URL, summarizeUrl.trim())
            .putString(KEY_STATS_URL, statsUrl.trim())
            .putString(KEY_OWN_NAME, ownName.trim())
            .putString(KEY_MSG_ID, msgId.trim())
            .putString(KEY_NAME_ID, nameId.trim())
            .putString(KEY_TIME_ID, timeId.trim())
            .putString(KEY_TITLE_ID, titleId.trim())
            .putString(KEY_MENTION_KEYWORD, mentionKeyword.trim())
            .putString(KEY_SUMMARY_KEYWORD, summaryKeyword.trim())
            .putString(KEY_INPUT_ID, inputId.trim())
            .putString(KEY_SEND_ID, sendId.trim())
            .putBoolean(KEY_CALIBRATE, calibrate)
            .apply()
    }

    /** 토큰이 실제로 채워졌는지(플레이스홀더/빈값이 아닌지). */
    fun isTokenSet(): Boolean =
        token.isNotBlank() && !token.startsWith("PUT_")
}
