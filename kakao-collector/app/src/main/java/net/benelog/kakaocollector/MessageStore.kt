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
 */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "collector.db", null, 2) {

    companion object {
        // 스크롤(백필) 재수집에서 같은 본문이 '하루 인접한 다른 날짜'로 다시 들어오는 것은
        // 날짜 경계 오부여(스티키 뱃지 지연/구분선 미포착)다 — 단, 그 재수집은 같은 스크롤
        // 세션(수 분) 안에서 일어나므로, 기존 행이 이 시간창 안에서 수집된 경우로 제한한다.
        // 진짜로 며칠 뒤 같은 말을 반복한 메시지까지 합쳐버리지 않기 위한 안전핀.
        private const val CROSS_DAY_RESCRAPE_WINDOW_MS = 6L * 60 * 60 * 1000
    }

    enum class Outcome { INSERTED, UPDATED, SKIPPED }
    data class Result(val outcome: Outcome, val rowId: Long)
    data class UnsentRow(val id: Long, val room: String, val sender: String, val text: String, val clientTime: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "room TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, " +
                "client_time TEXT, collected_at INTEGER NOT NULL, " +
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
    }

    /** 같은 날(또는 한쪽이 빈값)로 볼 수 있으면 true — 알려진 다른 날끼리는 합치지 않는다. */
    private fun ctCompatible(a: String, b: String): Boolean = a == b || a.isEmpty() || b.isEmpty()

    /**
     * 잘림/편집 병합 후 기록한다.
     *  - 같은 방 최근 행 중 본문이 이 메시지의 '더 짧은 앞부분'인 행이 있으면 그 행을 in-place로
     *    갱신(원래 _id/collected_at 유지 = 대화 순서 보존) → UPDATED.
     *  - 반대로 이 메시지가 기존 행의 잘린 앞부분이면(기존이 더 완전) 버린다 → SKIPPED.
     *  - 아니면 새 행 INSERT(정확중복이면 UNIQUE로 무시) → INSERTED / SKIPPED.
     *
     * [fromScroll]=true(스크롤 settle 수집)면 날짜 경계 가드가 추가로 작동한다: 같은 본문이
     * 방금(시간창 내) '하루 인접한 다른 날짜'로 저장돼 있으면 재수집 오부여로 보고 버린다.
     * 실시간 수집(false)엔 적용하지 않는다 — 자정 전후로 같은 말을 정말로 두 번 보낸
     * 메시지를 지우면 안 되기 때문.
     */
    fun recordOrMerge(room: String, sender: String, text: String, clientTime: String, nowMillis: Long, fromScroll: Boolean = false): Result {
        val db = writableDatabase
        var updateId = -1L
        var updText = text
        var updCt = clientTime
        var skip = false
        db.query(
            "messages", arrayOf("_id", "text", "client_time", "collected_at"),
            "room=?", arrayOf(room), null, null, "_id DESC", "400",
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val etext = c.getString(1) ?: ""
                val ect = c.getString(2) ?: ""
                val ecollected = c.getLong(3)
                if (etext == text) {
                    if (ect == clientTime) { skip = true; break } // 정확 중복
                    if (ctCompatible(ect, clientTime)) {
                        // 빈 날짜→날짜 전환: 같은 메시지. 기존 행의 날짜만 제자리 업그레이드(중복 X).
                        if (ect.isEmpty() && clientTime.isNotEmpty()) { updateId = id; updCt = clientTime; break }
                        skip = true; break // 기존이 이미 날짜 있음(또는 둘 다 빈값)
                    }
                    // 날짜 경계 가드(2026-07-05 오수집 재발 방지): 스크롤 재수집 + 방금 수집된
                    // 같은 본문 + 하루 인접 날짜 = 같은 메시지의 날짜 오부여 사본 → 버림.
                    if (fromScroll && nowMillis - ecollected <= CROSS_DAY_RESCRAPE_WINDOW_MS &&
                        KakaoDate.isAdjacentDay(ect, clientTime)
                    ) { skip = true; break }
                    continue // 같은 본문, 다른 '아는' 날 → 다른 메시지
                }
                if (!ctCompatible(ect, clientTime)) continue
                if (KakaoText.extends(etext, text)) { updateId = id; updCt = ect; break } // 기존이 짧음 → 채워 넣기
                if (KakaoText.extends(text, etext)) { skip = true; break } // 들어온 게 잘린 것 → 버림
            }
        }
        if (updateId >= 0) {
            updateRow(updateId, updText, updCt)
            return Result(Outcome.UPDATED, updateId)
        }
        if (skip) return Result(Outcome.SKIPPED, -1)

        val v = ContentValues().apply {
            put("room", room)
            put("sender", sender)
            put("text", text)
            put("client_time", clientTime)
            put("collected_at", nowMillis)
            put("sent_ok", 0)
        }
        val id = db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id >= 0) Result(Outcome.INSERTED, id) else Result(Outcome.SKIPPED, -1)
    }

    /** 기존 행을 더 완전한 본문/날짜로 in-place 갱신(순서 보존). 재전송 위해 sent_ok 초기화. */
    private fun updateRow(rowId: Long, text: String, clientTime: String) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("text", text); put("client_time", clientTime); put("sent_ok", 0) },
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
            "messages", arrayOf("_id", "room", "sender", "text", "client_time"),
            "sent_ok=0 AND collected_at>=?", arrayOf(sinceMillis.toString()), null, null, "_id ASC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(UnsentRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: ""))
            }
        }
        return out
    }

    fun prune(cutoffMillis: Long) {
        writableDatabase.delete("messages", "collected_at < ?", arrayOf(cutoffMillis.toString()))
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
