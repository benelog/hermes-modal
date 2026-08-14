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
 * DB 레벨에서 잘림/편집(짧은→긴) 메시지를 같은 행에 합쳐 넣는다(판단 규칙은 [MergePolicy]).
 * UNIQUE(room,sender,text,client_time)는 같은 본문 정확중복에 대한 백스톱이다.
 * 감사/디버깅용으로 sent_ok·collected_at 도 보관.
 *
 * sent_time(HH:MM, 발신 시각)은 dedupe 키가 **아니다** — 시각 결합도 좌표 휴리스틱이라
 * 키에 넣으면 sender 때처럼 행이 갈라진다. 대신 같은 행의 누락 필드로 취급해, 재수집에서
 * 시각이 잡히면 제자리 승급(빈값→시각)하고 이미 있으면 더 이른 값([KakaoTime.earliest])을 남긴다.
 */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "collector.db", null, 3) {

    enum class Outcome { INSERTED, UPDATED, SKIPPED }

    /** 저장 결과. UPDATED면 text/clientTime/sentTime 은 병합 후 실제 DB 값 — 서버에도 이 값을 보낸다. */
    data class Result(
        val outcome: Outcome,
        val rowId: Long,
        val text: String = "",
        val clientTime: String = "",
        val sentTime: String = "",
    )

    /** 전송 실패로 남은 행(재전송 대상). */
    data class UnsentRow(val id: Long, val record: MessageRecord)

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

    /**
     * 잘림/편집 병합 후 기록한다. 같은 방의 최근 행들을 [MergePolicy.decide]로 비교해
     * 첫 매칭에 따라 in-place 갱신(UPDATED)/버림(SKIPPED)하고, 매칭이 없으면 새 행
     * INSERT(정확중복이면 UNIQUE로 무시 → SKIPPED). [fromScroll]은 스크롤 settle 수집
     * 여부 — [MergePolicy]의 날짜 경계 가드용.
     */
    fun recordOrMerge(record: MessageRecord, nowMillis: Long, fromScroll: Boolean = false): Result {
        val db = writableDatabase
        var decision: MergePolicy.Decision = MergePolicy.Decision.NoMatch
        db.query(
            "messages", arrayOf("_id", "text", "client_time", "sent_time", "collected_at"),
            // 스캔 범위 1000행: 백필(며칠치 한 번에)에서도 같은 메시지의 빈 날짜/시각 행을
            // 찾아 제자리 승급할 수 있게. 이 밖의 행은 UNIQUE 백스톱 + 서버 병합이 거른다.
            "room=?", arrayOf(record.room), null, null, "_id DESC", "1000",
        ).use { c ->
            while (c.moveToNext()) {
                val existing = MergePolicy.ExistingRow(
                    id = c.getLong(0),
                    text = c.getString(1) ?: "",
                    clientTime = c.getString(2) ?: "",
                    sentTime = c.getString(3) ?: "",
                    collectedAt = c.getLong(4),
                )
                decision = MergePolicy.decide(existing, record, nowMillis, fromScroll)
                if (decision != MergePolicy.Decision.NoMatch) break
            }
        }
        when (val d = decision) {
            is MergePolicy.Decision.Update -> {
                updateRow(d.id, d.text, d.clientTime, d.sentTime)
                return Result(Outcome.UPDATED, d.id, d.text, d.clientTime, d.sentTime)
            }
            MergePolicy.Decision.Skip -> return Result(Outcome.SKIPPED, -1)
            MergePolicy.Decision.NoMatch -> {}
        }

        val v = ContentValues().apply {
            put("room", record.room)
            put("sender", record.sender)
            put("text", record.text)
            put("client_time", record.clientTime)
            put("sent_time", record.sentTime)
            put("collected_at", nowMillis)
            put("sent_ok", 0)
        }
        val id = db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id >= 0) {
            Result(Outcome.INSERTED, id, record.text, record.clientTime, record.sentTime)
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
                        id = c.getLong(0),
                        record = MessageRecord(
                            room = c.getString(1),
                            sender = c.getString(2),
                            text = c.getString(3),
                            clientTime = c.getString(4) ?: "",
                            sentTime = c.getString(5) ?: "",
                        ),
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

    /**
     * '이번 검증 범위와 관련된' 미전송(sent_ok=0) 행 수 — 아직 서버에 안 간 것의 직접 증거.
     * 발신일이 범위 안이거나, 날짜 미상이면 최근 48시간(재전송 창) 안에 수집된 행만 센다 —
     * 그보다 오래된 미전송(legacy, 재전송 창 밖이라 영구 보류)은 이번 범위의 전송 상태와
     * 무관한데 방 전체를 세면 검증이 매번 헛경보를 낸다(2026-07-13 실측: 172건).
     */
    fun unsentCountInRange(room: String, start: String, end: String, nowMillis: Long): Int {
        val recentCutoff = nowMillis - 48L * 60 * 60 * 1000
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE room=? AND sent_ok=0 AND " +
                "((client_time<>'' AND client_time>=? AND client_time<=?) OR " +
                "(client_time='' AND collected_at>=?))",
            arrayOf(room, start, end, recentCutoff.toString()),
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
