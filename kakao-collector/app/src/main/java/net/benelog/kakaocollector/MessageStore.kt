package net.benelog.kakaocollector

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 수집 메시지 로컬 저장소.
 *
 * 중복제거는 두 겹이다: (1) [KakaoCollectorService] 의 인메모리 seen 집합이 [DedupeKey]
 * (room,text,client_time — **sender 제외**)로 1차 차단하고, (2) 여기 [recordOrMerge] 가
 * DB 레벨에서 잘림/편집(짧은→긴) 메시지를 같은 행에 합쳐 넣는다. UNIQUE(room,sender,text,client_time)
 * 는 같은 본문 정확중복에 대한 백스톱이다. 감사/디버깅용으로 sent_ok·collected_at 도 보관.
 *
 * sent_time(HH:MM, 발신 시각)은 dedupe 키가 **아니다** — 시각 결합도 좌표 휴리스틱이라
 * 키에 넣으면 sender 때처럼 행이 갈라진다. 대신 같은 행의 누락 필드로 취급해, 재수집에서
 * 시각이 잡히면 제자리 승급(빈값→시각)하고 이미 있으면 더 이른 값([KakaoTime.earliest])을 남긴다.
 */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "collector.db", null, 3) {

    companion object {
        // 스크롤(백필) 재수집에서 같은 본문이 '하루 인접한 다른 날짜'로 다시 들어오는 것은
        // 날짜 경계 오부여(스티키 뱃지 지연/구분선 미포착)다 — 단, 그 재수집은 같은 스크롤
        // 세션(수 분) 안에서 일어나므로, 기존 행이 이 시간창 안에서 수집된 경우로 제한한다.
        // 진짜로 며칠 뒤 같은 말을 반복한 메시지까지 합쳐버리지 않기 위한 안전핀.
        private const val CROSS_DAY_RESCRAPE_WINDOW_MS = 6L * 60 * 60 * 1000
    }

    enum class Outcome { INSERTED, UPDATED, SKIPPED }

    /** 저장 결과. UPDATED면 text/clientTime/sentTime 은 병합 후 실제 DB 값 — 서버에도 이 값을 보낸다. */
    data class Result(
        val outcome: Outcome,
        val rowId: Long,
        val text: String = "",
        val clientTime: String = "",
        val sentTime: String = "",
    )
    data class UnsentRow(
        val id: Long,
        val room: String,
        val sender: String,
        val text: String,
        val clientTime: String,
        val sentTime: String,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "room TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, " +
                "client_time TEXT, sent_time TEXT NOT NULL DEFAULT '', " +
                "collected_at INTEGER NOT NULL, " +
                "sent_ok INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE(room, sender, text, client_time))",
        )
        // prune(collected_at < ?) 의 보관기간 정리가 풀스캔하지 않도록 인덱스.
        db.execSQL("CREATE INDEX idx_messages_collected_at ON messages(collected_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_collected_at ON messages(collected_at)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sent_time TEXT NOT NULL DEFAULT ''")
        }
    }

    /** 같은 날(또는 한쪽이 빈값)로 볼 수 있으면 true — 알려진 다른 날끼리는 합치지 않는다. */
    private fun ctCompatible(a: String, b: String): Boolean = a == b || a.isEmpty() || b.isEmpty()

    /**
     * 잘림/편집 병합 후 기록한다.
     *  - 같은 방 최근 행 중 같은 본문(호환 날짜)이 있으면 그 행의 누락 필드를 제자리 승급:
     *    빈 날짜→날짜, 빈 시각→시각(있으면 더 이른 값 유지). 바뀐 게 없으면 SKIPPED.
     *  - 본문이 이 메시지의 '더 짧은 앞부분'인 행이 있으면 그 행을 in-place로 갱신
     *    (원래 _id/collected_at 유지 = 대화 순서 보존) → UPDATED.
     *  - 반대로 이 메시지가 기존 행의 잘린 앞부분이면(기존이 더 완전) 본문은 버리되
     *    날짜/시각은 채울 수 있으면 채운다 → UPDATED / SKIPPED.
     *  - 아니면 새 행 INSERT(정확중복이면 UNIQUE로 무시) → INSERTED / SKIPPED.
     *
     * [fromScroll]=true(스크롤 settle 수집)면 날짜 경계 가드가 추가로 작동한다: 같은 본문이
     * 방금(시간창 내) '하루 인접한 다른 날짜'로 저장돼 있으면 재수집 오부여로 보고 버린다.
     * 실시간 수집(false)엔 적용하지 않는다 — 자정 전후로 같은 말을 정말로 두 번 보낸
     * 메시지를 지우면 안 되기 때문.
     */
    fun recordOrMerge(
        room: String,
        sender: String,
        text: String,
        clientTime: String,
        sentTime: String,
        nowMillis: Long,
        fromScroll: Boolean = false,
    ): Result {
        val db = writableDatabase
        var updateId = -1L
        var updText = text
        var updCt = clientTime
        var updSt = sentTime
        var skip = false
        db.query(
            "messages", arrayOf("_id", "text", "client_time", "sent_time", "collected_at"),
            // 스캔 범위 1000행: 백필(며칠치 한 번에)에서도 같은 메시지의 빈 날짜/시각 행을
            // 찾아 제자리 승급할 수 있게. 이 밖의 행은 UNIQUE 백스톱 + 서버 병합이 거른다.
            "room=?", arrayOf(room), null, null, "_id DESC", "1000",
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val etext = c.getString(1) ?: ""
                val ect = c.getString(2) ?: ""
                val est = c.getString(3) ?: ""
                val ecollected = c.getLong(4)
                if (etext == text) {
                    if (ctCompatible(ect, clientTime)) {
                        // 같은 메시지 — 누락 필드만 제자리 승급(빈 날짜→날짜, 시각은 이른 값).
                        val mergedCt = ect.ifEmpty { clientTime }
                        val mergedSt = KakaoTime.earliest(est, sentTime)
                        if (mergedCt != ect || mergedSt != est) {
                            updateId = id; updText = etext; updCt = mergedCt; updSt = mergedSt
                        } else {
                            skip = true
                        }
                        break
                    }
                    // 날짜 경계 가드(2026-07-05 오수집 재발 방지): 스크롤 재수집 + 방금 수집된
                    // 같은 본문 + 하루 인접 날짜 = 같은 메시지의 날짜 오부여 사본 → 버림.
                    if (fromScroll && nowMillis - ecollected <= CROSS_DAY_RESCRAPE_WINDOW_MS &&
                        KakaoDate.isAdjacentDay(ect, clientTime)
                    ) { skip = true; break }
                    continue // 같은 본문, 다른 '아는' 날 → 다른 메시지
                }
                if (!ctCompatible(ect, clientTime)) continue
                if (KakaoText.extends(etext, text)) { // 기존이 짧음 → 완전한 본문으로 채워 넣기
                    updateId = id; updText = text
                    updCt = ect.ifEmpty { clientTime }
                    updSt = KakaoTime.earliest(est, sentTime)
                    break
                }
                if (KakaoText.extends(text, etext)) { // 들어온 게 잘린 것 → 본문은 버리되 날짜/시각은 채움
                    val mergedCt = ect.ifEmpty { clientTime }
                    val mergedSt = KakaoTime.earliest(est, sentTime)
                    if (mergedCt != ect || mergedSt != est) {
                        updateId = id; updText = etext; updCt = mergedCt; updSt = mergedSt
                    } else {
                        skip = true
                    }
                    break
                }
            }
        }
        if (updateId >= 0) {
            updateRow(updateId, updText, updCt, updSt)
            return Result(Outcome.UPDATED, updateId, updText, updCt, updSt)
        }
        if (skip) return Result(Outcome.SKIPPED, -1)

        val v = ContentValues().apply {
            put("room", room)
            put("sender", sender)
            put("text", text)
            put("client_time", clientTime)
            put("sent_time", sentTime)
            put("collected_at", nowMillis)
            put("sent_ok", 0)
        }
        val id = db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id >= 0) {
            Result(Outcome.INSERTED, id, text, clientTime, sentTime)
        } else {
            Result(Outcome.SKIPPED, -1)
        }
    }

    /** 기존 행을 더 완전한 본문/날짜/시각으로 in-place 갱신(순서 보존). 재전송 위해 sent_ok 초기화. */
    private fun updateRow(rowId: Long, text: String, clientTime: String, sentTime: String) {
        writableDatabase.update(
            "messages",
            ContentValues().apply {
                put("text", text)
                put("client_time", clientTime)
                put("sent_time", sentTime)
                put("sent_ok", 0)
            },
            "_id=?", arrayOf(rowId.toString()),
        )
    }

    fun markSent(rowId: Long) {
        writableDatabase.update("messages", ContentValues().apply { put("sent_ok", 1) }, "_id=?", arrayOf(rowId.toString()))
    }

    /** 전송 실패로 남은(sent_ok=0) 행을 오래된 것부터. 재전송(flush) 대상 조회용. */
    fun unsentRows(sinceMillis: Long, limit: Int): List<UnsentRow> {
        val out = ArrayList<UnsentRow>()
        readableDatabase.query(
            "messages", arrayOf("_id", "room", "sender", "text", "client_time", "sent_time"),
            "sent_ok=0 AND collected_at>=?", arrayOf(sinceMillis.toString()), null, null, "_id ASC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    UnsentRow(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4) ?: "", c.getString(5) ?: "",
                    ),
                )
            }
        }
        return out
    }

    fun prune(cutoffMillis: Long) {
        writableDatabase.delete("messages", "collected_at < ?", arrayOf(cutoffMillis.toString()))
    }

    /**
     * 발신일(client_time)별 '중복제거 키 기준' 건수 — 전송 검증용. sender 흔들림으로 같은
     * 키가 여러 행일 수 있어 DISTINCT text 로 센다(서버는 키당 레코드 1개라 레코드 수와 대응).
     * 범위는 ISO 날짜 문자열 [start, end] 포함(사전순 == 시간순).
     */
    fun distinctCountsByDate(room: String, start: String, end: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT client_time, COUNT(DISTINCT text) FROM messages " +
                "WHERE room=? AND client_time>=? AND client_time<=? AND client_time<>'' " +
                "GROUP BY client_time ORDER BY client_time",
            arrayOf(room, start, end),
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return out
    }

    /** 이 방의 미전송(sent_ok=0) 행 수 — 전송 검증에서 '아직 안 간 것'의 직접 증거. */
    fun unsentCount(room: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE room=? AND sent_ok=0", arrayOf(room),
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 최근 행들의 dedupe 키(인메모리 seen 시드용). sender 제외(room,text,client_time). */
    fun recentKeys(limit: Int): Set<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.query(
            "messages", arrayOf("room", "text", "client_time"),
            null, null, null, null, "_id DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(DedupeKey.of(c.getString(0), c.getString(1), c.getString(2) ?: ""))
            }
        }
        return out
    }
}
