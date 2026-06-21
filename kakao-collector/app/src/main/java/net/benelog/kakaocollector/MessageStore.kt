package net.benelog.kakaocollector

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 수집 메시지 로컬 저장소. UNIQUE(room,sender,text,client_time) + INSERT OR IGNORE 가 중복제거 단일 출처.
 * 감사/디버깅용으로 sent_ok(전송 성공 여부)와 collected_at(수집 시각)도 보관.
 */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "collector.db", null, 2) {

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

    /** 새 행이면 rowId(>=0), 이미 있으면 -1. */
    fun recordNew(room: String, sender: String, text: String, clientTime: String, nowMillis: Long): Long {
        val v = ContentValues().apply {
            put("room", room)
            put("sender", sender)
            put("text", text)
            put("client_time", clientTime)
            put("collected_at", nowMillis)
            put("sent_ok", 0)
        }
        return writableDatabase.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun markSent(rowId: Long) {
        writableDatabase.update("messages", ContentValues().apply { put("sent_ok", 1) }, "_id=?", arrayOf(rowId.toString()))
    }

    fun prune(cutoffMillis: Long) {
        writableDatabase.delete("messages", "collected_at < ?", arrayOf(cutoffMillis.toString()))
    }

    /** 최근 행들의 dedupe 키(인메모리 seen 시드용). */
    fun recentKeys(limit: Int): Set<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.query(
            "messages", arrayOf("room", "sender", "text", "client_time"),
            null, null, null, null, "_id DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(DedupeKey.of(c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: ""))
            }
        }
        return out
    }
}
