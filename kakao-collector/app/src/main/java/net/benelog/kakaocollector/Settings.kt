package net.benelog.kakaocollector

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱에서 편집 가능한 런타임 설정. SharedPreferences에 저장하므로 토큰/URL 같은 실값을
 * 코드(=git)에 두지 않아도 된다. 한 번도 저장한 적 없는 키는 [Config]의 기본값으로 폴백한다.
 *
 * [Application][CollectorApp]에서 [init]을 호출해 두면 어디서든 바로 읽을 수 있다.
 */
object Settings {
    private const val PREFS_NAME = "kakao_collector"

    private const val KEY_TOKEN = "token"
    private const val KEY_INGEST_URL = "ingest_url"
    private const val KEY_SUMMARIZE_URL = "summarize_url"
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

    // 저장된 적 있으면 그 값(빈 문자열 포함 = 의도적으로 비활성화한 id)을, 없으면 기본값.
    private fun get(key: String, default: String): String =
        if (prefs.contains(key)) prefs.getString(key, default) ?: default else default

    var token: String
        get() = get(KEY_TOKEN, Config.TOKEN)
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var ingestUrl: String
        get() = get(KEY_INGEST_URL, Config.INGEST_URL)
        set(v) = prefs.edit().putString(KEY_INGEST_URL, v).apply()

    var summarizeUrl: String
        get() = get(KEY_SUMMARIZE_URL, Config.SUMMARIZE_URL)
        set(v) = prefs.edit().putString(KEY_SUMMARIZE_URL, v).apply()

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

    /** 내 카톡 닉네임. 내 메시지엔 닉네임이 안 떠서, 보낸이로 채울 값. */
    var ownName: String
        get() = get(KEY_OWN_NAME, Config.OWN_NAME)
        set(v) = prefs.edit().putString(KEY_OWN_NAME, v).apply()

    var msgId: String
        get() = get(KEY_MSG_ID, Config.MSG_ID)
        set(v) = prefs.edit().putString(KEY_MSG_ID, v).apply()

    var nameId: String
        get() = get(KEY_NAME_ID, Config.NAME_ID)
        set(v) = prefs.edit().putString(KEY_NAME_ID, v).apply()

    var timeId: String
        get() = get(KEY_TIME_ID, Config.TIME_ID)
        set(v) = prefs.edit().putString(KEY_TIME_ID, v).apply()

    /** 날짜 구분 노드 id(메시지에 일자 결합용). 분 시각 미노출의 대체 수단. */
    var dateIndicatorId: String
        get() = get(KEY_DATE_INDICATOR_ID, Config.DATE_INDICATOR_ID)
        set(v) = prefs.edit().putString(KEY_DATE_INDICATOR_ID, v).apply()

    var titleId: String
        get() = get(KEY_TITLE_ID, Config.TITLE_ID)
        set(v) = prefs.edit().putString(KEY_TITLE_ID, v).apply()

    /** 멘션 키워드 raw(비어도 됨). 트리거 판정엔 [effectiveMention] 사용. */
    var mentionKeyword: String
        get() = get(KEY_MENTION_KEYWORD, Config.MENTION_KEYWORD)
        set(v) = prefs.edit().putString(KEY_MENTION_KEYWORD, v).apply()

    /** 실제 트리거에 쓰는 멘션 키워드: 비어 있으면 내 닉네임(ownName)으로 폴백. */
    fun effectiveMention(): String = mentionKeyword.ifBlank { ownName }

    var summaryKeyword: String
        get() = get(KEY_SUMMARY_KEYWORD, Config.SUMMARY_KEYWORD)
        set(v) = prefs.edit().putString(KEY_SUMMARY_KEYWORD, v).apply()

    /** 카톡 입력창 EditText id. */
    var inputId: String
        get() = get(KEY_INPUT_ID, Config.INPUT_ID)
        set(v) = prefs.edit().putString(KEY_INPUT_ID, v).apply()

    /** 카톡 전송 버튼 id. */
    var sendId: String
        get() = get(KEY_SEND_ID, Config.SEND_ID)
        set(v) = prefs.edit().putString(KEY_SEND_ID, v).apply()

    /** 방 멘션 요약 감지 시 그 방으로 자동 발신할지. 기본 false(캘리브레이션 후 ON). */
    var autoReply: Boolean
        get() = if (prefs.contains(KEY_AUTO_REPLY)) {
            prefs.getBoolean(KEY_AUTO_REPLY, Config.AUTO_REPLY)
        } else {
            Config.AUTO_REPLY
        }
        set(v) = prefs.edit().putBoolean(KEY_AUTO_REPLY, v).apply()

    /** 봇 발신 마커(루프 방지용 접두). */
    val botMarker: String get() = Config.BOT_MARKER

    var calibrate: Boolean
        get() = if (prefs.contains(KEY_CALIBRATE)) {
            prefs.getBoolean(KEY_CALIBRATE, Config.CALIBRATE)
        } else {
            Config.CALIBRATE
        }
        set(v) = prefs.edit().putBoolean(KEY_CALIBRATE, v).apply()

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
