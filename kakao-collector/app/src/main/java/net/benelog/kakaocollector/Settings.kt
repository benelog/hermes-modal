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
    private const val KEY_ROOM_NAME = "room_name"
    private const val KEY_OWN_NAME = "own_name"
    private const val KEY_MSG_ID = "msg_id"
    private const val KEY_NAME_ID = "name_id"
    private const val KEY_TIME_ID = "time_id"
    private const val KEY_TITLE_ID = "title_id"
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

    var roomName: String
        get() = get(KEY_ROOM_NAME, Config.ROOM_NAME)
        set(v) = prefs.edit().putString(KEY_ROOM_NAME, v).apply()

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

    var titleId: String
        get() = get(KEY_TITLE_ID, Config.TITLE_ID)
        set(v) = prefs.edit().putString(KEY_TITLE_ID, v).apply()

    var calibrate: Boolean
        get() = if (prefs.contains(KEY_CALIBRATE)) {
            prefs.getBoolean(KEY_CALIBRATE, Config.CALIBRATE)
        } else {
            Config.CALIBRATE
        }
        set(v) = prefs.edit().putBoolean(KEY_CALIBRATE, v).apply()

    /** 폼 한 번에 저장(단일 트랜잭션). 문자열은 앞뒤 공백 제거. */
    fun save(
        token: String,
        ingestUrl: String,
        roomName: String,
        ownName: String,
        msgId: String,
        nameId: String,
        timeId: String,
        titleId: String,
        calibrate: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_INGEST_URL, ingestUrl.trim())
            .putString(KEY_ROOM_NAME, roomName.trim())
            .putString(KEY_OWN_NAME, ownName.trim())
            .putString(KEY_MSG_ID, msgId.trim())
            .putString(KEY_NAME_ID, nameId.trim())
            .putString(KEY_TIME_ID, timeId.trim())
            .putString(KEY_TITLE_ID, titleId.trim())
            .putBoolean(KEY_CALIBRATE, calibrate)
            .apply()
    }

    /** 토큰이 실제로 채워졌는지(플레이스홀더/빈값이 아닌지). */
    fun isTokenSet(): Boolean =
        token.isNotBlank() && !token.startsWith("PUT_")
}
