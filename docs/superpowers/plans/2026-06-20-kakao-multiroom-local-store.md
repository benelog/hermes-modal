# Kakao Collector 멀티룸 + 로컬 영속 저장 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수집 앱을 여러 대화방 대상으로 확장하고, 수집 내역을 폰 SQLite에 영속 저장해 재시작 후에도 같은 메시지를 재전송하지 않게 한다.

**Architecture:** 순수 로직(중복키 생성·방 매칭 파싱)은 Android 비의존 객체로 빼서 JVM 단위테스트(TDD). 로컬 저장은 `SQLiteOpenHelper`(`MessageStore`)의 `UNIQUE(room,sender,text,client_time)` + `INSERT OR IGNORE`가 중복제거 단일 출처. 수집은 인메모리 `seen`(시작 시 DB 최근키로 시드)으로 빠르게 거르고, 백그라운드 `Uploader`가 DB 기록→새 것만 POST→`sent_ok` 갱신. Modal/서버는 이미 멀티룸이라 변경 없음.

**Tech Stack:** Kotlin, Android(minSdk 26), AccessibilityService, SQLite(SQLiteOpenHelper), JUnit4(JVM 단위테스트). 빌드 JDK 17 + `./install.sh`.

**Spec:** `docs/superpowers/specs/2026-06-20-kakao-multiroom-local-store-design.md`

**검증 방침:** 순수 로직은 JVM 단위테스트로 TDD. Android 의존(Settings/MessageStore/Service)은 이 앱에 계측 테스트 인프라가 없으므로 **실기기 검증**(빌드 + adb 구동 + `dump_db.sh`)으로 확인 — 각 Task에 정확한 명령/기대값 포함.

**공통 빌드/검증 명령:**
- JVM 단위테스트: `cd kakao-collector && JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem" ./gradlew testDebugUnitTest`
- 빌드+설치: `cd kakao-collector && ./install.sh`
- 접근성 재바인딩: `cd kakao-collector && ./enable_service.sh`

---

## Task 1: JVM 단위테스트 인프라 + DedupeKey (순수, TDD)

**Files:**
- Modify: `kakao-collector/app/build.gradle.kts` (dependencies 블록)
- Create: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/DedupeKey.kt`
- Test: `kakao-collector/app/src/test/java/net/benelog/kakaocollector/DedupeKeyTest.kt`

- [ ] **Step 1: junit 테스트 의존성 추가**

`app/build.gradle.kts`의 `dependencies { ... }`를 아래로 교체:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`app/src/test/java/net/benelog/kakaocollector/DedupeKeyTest.kt`:

```kotlin
package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DedupeKeyTest {
    @Test fun sameFieldsProduceSameKey() {
        assertEquals(
            DedupeKey.of("r", "s", "t", "12:00"),
            DedupeKey.of("r", "s", "t", "12:00"),
        )
    }

    @Test fun differentRoomProducesDifferentKey() {
        assertNotEquals(
            DedupeKey.of("roomA", "s", "t", ""),
            DedupeKey.of("roomB", "s", "t", ""),
        )
    }

    @Test fun fieldsAreSeparatedSoConcatenationCannotCollide() {
        // "ab"+""  vs  "a"+"b" 가 구분자 없이는 충돌. 구분자(U+0001)로 분리되어야 함.
        assertNotEquals(
            DedupeKey.of("ab", "", "t", ""),
            DedupeKey.of("a", "b", "t", ""),
        )
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd kakao-collector && JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem" ./gradlew testDebugUnitTest`
Expected: FAIL — `Unresolved reference: DedupeKey`

- [ ] **Step 4: DedupeKey 구현**

`app/src/main/java/net/benelog/kakaocollector/DedupeKey.kt`:

```kotlin
package net.benelog.kakaocollector

/**
 * 메시지 중복제거 키. 인메모리 seen 집합과 DB UNIQUE 의미를 한 곳에서 정의한다.
 * 서버 message_key와 동일하게 room을 포함하고 U+0001로 필드를 구분한다.
 */
object DedupeKey {
    private const val SEP = "\u0001"

    fun of(room: String, sender: String, text: String, clientTime: String): String =
        room + SEP + sender + SEP + text + SEP + clientTime
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd kakao-collector && JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem" ./gradlew testDebugUnitTest`
Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/app/build.gradle.kts \
  kakao-collector/app/src/main/java/net/benelog/kakaocollector/DedupeKey.kt \
  kakao-collector/app/src/test/java/net/benelog/kakaocollector/DedupeKeyTest.kt
git commit -m "DedupeKey: room 포함 중복키 + JVM 단위테스트 인프라"
```

---

## Task 2: RoomMatch (대상 방 목록 파싱·매칭, 순수, TDD)

**Files:**
- Create: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/RoomMatch.kt`
- Test: `kakao-collector/app/src/test/java/net/benelog/kakaocollector/RoomMatchTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/net/benelog/kakaocollector/RoomMatchTest.kt`:

```kotlin
package net.benelog.kakaocollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMatchTest {
    @Test fun parseSplitsTrimsAndDropsBlankLines() {
        assertEquals(listOf("A", "B"), RoomMatch.parse(" A \n\n  B\n"))
    }

    @Test fun matchReturnsTargetContainedInTitle() {
        // 제목엔 부가정보가 붙을 수 있으니 contains 로 매칭, 반환은 '대상(정규형)'.
        assertEquals("ABC(북클럽)", RoomMatch.match("ABC(북클럽)", listOf("X", "ABC(북클럽)")))
    }

    @Test fun matchReturnsNullWhenNoTargetMatches() {
        assertNull(RoomMatch.match("다른방", listOf("ABC(북클럽)")))
    }

    @Test fun matchIgnoresBlankTargets() {
        assertNull(RoomMatch.match("anything", listOf("")))
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd kakao-collector && JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem" ./gradlew testDebugUnitTest`
Expected: FAIL — `Unresolved reference: RoomMatch`

- [ ] **Step 3: RoomMatch 구현**

`app/src/main/java/net/benelog/kakaocollector/RoomMatch.kt`:

```kotlin
package net.benelog.kakaocollector

/** 대상 방 목록(여러 줄 문자열) 파싱 + 화면 제목 매칭. Android 비의존(순수). */
object RoomMatch {
    /** 줄바꿈 구분 raw 문자열 → 방 제목 목록(trim, 빈 줄 제거). */
    fun parse(raw: String): List<String> =
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** 화면 제목이 대상 중 하나를 포함하면 그 대상(정규형)을 반환, 없으면 null. */
    fun match(title: String, targets: List<String>): String? =
        targets.firstOrNull { it.isNotEmpty() && title.contains(it) }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd kakao-collector && JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem" ./gradlew testDebugUnitTest`
Expected: PASS (DedupeKey 3 + RoomMatch 4 = 7 tests)

- [ ] **Step 5: 커밋**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/app/src/main/java/net/benelog/kakaocollector/RoomMatch.kt \
  kakao-collector/app/src/test/java/net/benelog/kakaocollector/RoomMatchTest.kt
git commit -m "RoomMatch: 대상 방 목록 파싱·제목 매칭 + 단위테스트"
```

---

## Task 3: Settings — 멀티룸(roomNames) + 마이그레이션

**Files:**
- Modify: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/Settings.kt`

기존 `Settings.kt`는 단일 `roomName`(KEY_ROOM_NAME)을 가진다. 목록으로 일반화하되, 기존 단일 값이
있으면 폴백해 기존 설치가 깨지지 않게 한다.

- [ ] **Step 1: KEY 추가**

`Settings.kt`에서 키 상수 블록에 추가(`KEY_ROOM_NAME` 줄 바로 아래):

```kotlin
    private const val KEY_ROOM_NAMES = "room_names"
```

- [ ] **Step 2: roomName 프로퍼티를 roomNames 기반으로 교체**

`Settings.kt`의 기존 블록:

```kotlin
    var roomName: String
        get() = get(KEY_ROOM_NAME, Config.ROOM_NAME)
        set(v) = prefs.edit().putString(KEY_ROOM_NAME, v).apply()
```

을 아래로 교체:

```kotlin
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
```

- [ ] **Step 3: save() 시그니처에서 roomName → roomNames**

`Settings.kt`의 `save(...)`에서 `roomName: String,` 파라미터를 `roomNames: String,`로 바꾸고,
`.putString(KEY_ROOM_NAME, roomName.trim())` 줄을 아래로 교체:

```kotlin
            .putString(KEY_ROOM_NAMES, roomNames.trim())
```

(파라미터 순서: `token, ingestUrl, roomNames, ownName, msgId, nameId, timeId, titleId, calibrate`)

- [ ] **Step 4: 컴파일 확인(빌드)**

Run: `cd kakao-collector && ./install.sh assembleDebug`
Expected: `BUILD SUCCESSFUL` (MainActivity/Service의 roomName 참조는 다음 Task에서 고치므로, 이 Step에서
컴파일 에러가 나면 그 참조들이 남아있는 것 — Task 6/7에서 정리. 단독 빌드 통과를 원하면 Task 3·6·7을
연속 수행 후 빌드.)

> 참고: Settings만 바꾸면 `Settings.roomName`을 참조하는 Service/MainActivity가 컴파일 에러다.
> Task 6, 7에서 그 참조를 모두 교체한다. 이 Task의 커밋은 Task 6·7과 함께 빌드 통과를 확인한 뒤 해도 된다.

- [ ] **Step 5: 커밋(빌드 통과 시)**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/app/src/main/java/net/benelog/kakaocollector/Settings.kt
git commit -m "Settings: 단일 roomName → roomNames 목록(+기존 값 폴백)"
```

---

## Task 4: MessageStore (SQLite) — 로컬 저장 + 중복제거

**Files:**
- Create: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/MessageStore.kt`

- [ ] **Step 1: MessageStore 구현**

`app/src/main/java/net/benelog/kakaocollector/MessageStore.kt`:

```kotlin
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
    SQLiteOpenHelper(context.applicationContext, "collector.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "room TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, " +
                "client_time TEXT, collected_at INTEGER NOT NULL, " +
                "sent_ok INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE(room, sender, text, client_time))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd kakao-collector && ./install.sh assembleDebug`
Expected: `BUILD SUCCESSFUL` (Settings의 roomName 참조 에러가 남아있다면 Task 6·7과 함께 빌드)

- [ ] **Step 3: 커밋**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/app/src/main/java/net/benelog/kakaocollector/MessageStore.kt
git commit -m "MessageStore: SQLite 로컬 저장 + UNIQUE 중복제거"
```

---

## Task 5: Uploader + Poster 동기화 리팩터

**Files:**
- Create: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/Uploader.kt`
- Modify: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/Poster.kt`

- [ ] **Step 1: Poster.post 를 동기 Boolean 반환으로 변경**

`Poster.kt` 전체를 아래로 교체(자체 executor 제거 — 스레딩은 Uploader가 소유):

```kotlin
package net.benelog.kakaocollector

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 메시지 1건을 Modal /ingest 로 동기 POST. 200이면 true. (Uploader의 백그라운드 스레드에서 호출.) */
object Poster {
    fun post(rec: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(Settings.ingestUrl + "?token=" + URLEncoder.encode(Settings.token, "UTF-8"))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            conn.outputStream.use { it.write(rec.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) Log.w(KakaoCollectorService.TAG, "ingest http=$code")
            code == 200
        } catch (e: Exception) {
            Log.w(KakaoCollectorService.TAG, "post failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
```

- [ ] **Step 2: Uploader 구현**

`app/src/main/java/net/benelog/kakaocollector/Uploader.kt`:

```kotlin
package net.benelog.kakaocollector

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors

/** 수집 메시지를 로컬 저장(중복제거) 후 '새 것만' Modal로 전송한다. 모든 DB/네트워크 작업은 단일 백그라운드 스레드. */
object Uploader {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var store: MessageStore

    /** 시작 시 1회. store 준비 + 오래된 행 정리(retentionMillis 초과). */
    fun init(context: Context, retentionMillis: Long) {
        if (!::store.isInitialized) store = MessageStore(context.applicationContext)
        val cutoff = System.currentTimeMillis() - retentionMillis
        exec.execute { store.prune(cutoff) }
    }

    /** 인메모리 seen 시드용 — 최근 키. init 이후 호출. */
    fun recentKeys(limit: Int): Set<String> =
        if (::store.isInitialized) store.recentKeys(limit) else emptySet()

    /** 수집 메시지 제출: DB 기록 → 새 행이면 POST → 성공 시 sent_ok. 중복이면 아무것도 안 함. */
    fun submit(room: String, sender: String, text: String, ts: String) {
        exec.execute {
            val id = store.recordNew(room, sender, text, ts, System.currentTimeMillis())
            if (id < 0) return@execute
            val ok = Poster.post(
                JSONObject().put("room", room).put("sender", sender).put("text", text).put("ts", ts),
            )
            if (ok) store.markSent(id)
        }
    }

    /** 연결 테스트용: 저장하지 않고 즉시 POST(백그라운드). */
    fun testPost(room: String, sender: String, text: String) {
        exec.execute {
            Poster.post(JSONObject().put("room", room).put("sender", sender).put("text", text).put("ts", ""))
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd kakao-collector && ./install.sh assembleDebug`
Expected: `BUILD SUCCESSFUL`(MainActivity가 아직 옛 Poster.post(json) 호출 중이면 에러 — Task 7에서 교체)

- [ ] **Step 4: 커밋(Task 6·7 후 빌드 통과 시 함께 가능)**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/app/src/main/java/net/benelog/kakaocollector/Uploader.kt \
  kakao-collector/app/src/main/java/net/benelog/kakaocollector/Poster.kt
git commit -m "Uploader: store→post→sent_ok 백그라운드 제출, Poster 동기화"
```

---

## Task 6: KakaoCollectorService — 멀티룸 매칭 + activeRoom 태깅 + DB 중복제거

**Files:**
- Modify: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/KakaoCollectorService.kt`

- [ ] **Step 1: 필드 추가 (activeRoom, 보존기간 상수)**

`companion object` 블록의 상수에 추가:

```kotlin
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30일
```

그리고 `private var inTargetRoom = false` 아래에 추가:

```kotlin
    private var activeRoom = ""
```

- [ ] **Step 2: onServiceConnected 에서 Uploader 초기화 + seen 시드**

기존:

```kotlin
    override fun onServiceConnected() {
        super.onServiceConnected()
        Settings.init(this) // Application에서 이미 했지만 방어적으로(멱등).
    }
```

을 교체:

```kotlin
    override fun onServiceConnected() {
        super.onServiceConnected()
        Settings.init(this) // Application에서 이미 했지만 방어적으로(멱등).
        Uploader.init(this, RETENTION_MS)
        // 재시작해도 최근 수집분을 '이미 봄'으로 인식 → 재전송 방지.
        seen.addAll(Uploader.recentKeys(SEEN_CAP))
    }
```

- [ ] **Step 3: handle()의 방 매칭을 목록 기반 + activeRoom 으로 교체**

기존 handle() 내부의 매칭 블록:

```kotlin
            if (windowChanged || !inTargetRoom) {
                val title = visibleRoomTitle(root) // 제목 노드(id/name)가 보이면 그 텍스트, 없으면 null
                when {
                    title != null && title.contains(Settings.roomName) -> {
                        if (!inTargetRoom) Log.i(TAG, "entered target room")
                        inTargetRoom = true
                    }
                    title != null -> inTargetRoom = false
                    !inTargetRoom && roomNameVisible(root) -> {
                        Log.i(TAG, "entered target room")
                        inTargetRoom = true
                    }
                }
            }
            if (inTargetRoom) scrape(root)
```

을 교체:

```kotlin
            if (windowChanged || !inTargetRoom) {
                val targets = Settings.roomNamesList()
                val title = visibleRoomTitle(root) // 제목 노드(id/name)가 보이면 그 텍스트, 없으면 null
                val matched = title?.let { RoomMatch.match(it, targets) }
                when {
                    matched != null -> {
                        if (!inTargetRoom || activeRoom != matched) Log.i(TAG, "entered target room: $matched")
                        inTargetRoom = true
                        activeRoom = matched
                    }
                    // 제목이 보이는데 대상이 아님 → 다른 방으로 나간 것.
                    title != null -> inTargetRoom = false
                    // 제목 노드가 안 보임(일시 윈도우): 미입장 상태면 모든 윈도우에서 대상 이름 한 번 더 탐색.
                    !inTargetRoom -> {
                        val byWindow = matchedRoomInAnyWindow(root, targets)
                        if (byWindow != null) {
                            Log.i(TAG, "entered target room: $byWindow")
                            inTargetRoom = true
                            activeRoom = byWindow
                        }
                    }
                }
            }
            if (inTargetRoom && activeRoom.isNotEmpty()) scrape(root)
```

- [ ] **Step 4: roomNameVisible/treeContainsRoomName 을 목록 매칭으로 일반화**

기존 `roomNameVisible(activeRoot)` 와 `treeContainsRoomName(root)` 두 함수를 아래 `matchedRoomInAnyWindow` /
`matchedRoomInTree` 로 교체(이름의 단일 roomName 대신 targets 목록을 받아 매칭된 대상을 반환):

```kotlin
    /** 어느 윈도우든 대상 방 제목이 보이면 그 대상(정규형)을 반환, 없으면 null. */
    private fun matchedRoomInAnyWindow(activeRoot: AccessibilityNodeInfo, targets: List<String>): String? {
        matchedRoomInTree(activeRoot, targets)?.let { return it }
        val wins = windows ?: return null
        for (w in wins) {
            val r = w.root ?: continue
            if (r != activeRoot) matchedRoomInTree(r, targets)?.let { return it }
        }
        return null
    }

    private fun matchedRoomInTree(root: AccessibilityNodeInfo, targets: List<String>): String? {
        val titleId = Settings.titleId
        if (titleId.isNotEmpty()) {
            for (t in root.findAccessibilityNodeInfosByViewId(titleId)) {
                val txt = t.text?.toString() ?: continue
                RoomMatch.match(txt, targets)?.let { return it }
            }
        }
        // 제목 노드 id가 안 맞아도, 트리 어딘가에 대상 이름과 정확히 같은 텍스트가 있으면 인정.
        var found: String? = null
        walk(root) { n ->
            if (found == null) {
                val txt = n.text?.toString()
                if (txt != null) found = targets.firstOrNull { it.isNotEmpty() && txt == it }
            }
        }
        return found
    }
```

- [ ] **Step 5: scrape() 를 activeRoom 태깅 + DB 제출로 교체**

기존 scrape()에서 첫 줄 `val roomName = Settings.roomName` 을 삭제하고, 본문 POST 부분을 교체.
scrape() 본체의 `for (n in ordered)` 루프 안 msgId 분기를 아래로 교체:

```kotlin
                msgId.isNotEmpty() && id == msgId -> {
                    // 내 메시지엔 닉네임이 안 뜨고 '우측 정렬'된다(오른쪽 여백 < 왼쪽 여백).
                    n.getBoundsInScreen(rect)
                    val sender = if ((screenW - rect.right) < rect.left) ownName else curSender
                    // 보낸이를 모르면(내 닉네임 미설정/남 메시지인데 닉네임 화면밖) 건너뜀.
                    if (sender.isEmpty()) continue
                    val key = DedupeKey.of(activeRoom, sender, txt, curTime)
                    if (firstSeen(key)) {
                        newCount++
                        Uploader.submit(activeRoom, sender, txt, curTime)
                    }
                }
```

그리고 scrape() 시작부의 변수 선언에서 `val roomName = Settings.roomName` 줄을 제거(다른 곳에서 roomName 미사용).
`remember()` 메서드는 `firstSeen()`으로 이름만 바꾼다(동작 동일 — 캡 초과 시 가장 오래된 것 제거):

기존:

```kotlin
    /** 처음 보는 key면 기억하고 true. 용량 상한 초과 시 가장 오래된 것부터 제거. */
    private fun remember(key: String): Boolean {
```

을:

```kotlin
    /** 처음 보는 key면 기억하고 true. 용량 상한 초과 시 가장 오래된 것부터 제거. */
    private fun firstSeen(key: String): Boolean {
```

- [ ] **Step 6: 컴파일 확인**

Run: `cd kakao-collector && ./install.sh assembleDebug`
Expected: `BUILD SUCCESSFUL` (MainActivity의 Settings.roomName/Poster.post 참조가 남아 있으면 에러 — Task 7에서 정리)

---

## Task 7: MainActivity + 레이아웃 — 대상 방 목록 필드

**Files:**
- Modify: `kakao-collector/app/src/main/res/layout/activity_main.xml`
- Modify: `kakao-collector/app/src/main/java/net/benelog/kakaocollector/MainActivity.kt`

- [ ] **Step 1: 레이아웃의 방 이름 필드를 다중행 목록으로 교체**

`activity_main.xml`의 기존 블록:

```xml
        <TextView
            style="@style/FieldLabel"
            android:text="방 이름" />

        <EditText
            android:id="@+id/etRoom"
            style="@style/FieldInput"
            android:hint="아카라카북클럽"
            android:inputType="text" />
```

을 교체:

```xml
        <TextView
            style="@style/FieldLabel"
            android:text="대상 방 목록 (한 줄에 방 제목 하나)" />

        <EditText
            android:id="@+id/etRoom"
            style="@style/FieldInput"
            android:hint="ABC(아카라카북클럽)"
            android:gravity="top"
            android:inputType="textMultiLine"
            android:minLines="2"
            android:singleLine="false" />
```

- [ ] **Step 2: MainActivity — populate/save/test/info 를 roomNames 로 교체**

`populateForm()`의 `etRoom.setText(Settings.roomName)` 를 교체:

```kotlin
        etRoom.setText(Settings.roomNamesRaw)
```

`saveForm()`의 `roomName = etRoom.text.toString(),` 를 교체:

```kotlin
            roomNames = etRoom.text.toString(),
```

`btnTest` 클릭 리스너의 `Poster.post(...)` 호출 블록:

```kotlin
            Poster.post(
                JSONObject()
                    .put("room", Settings.roomName)
                    .put("sender", "앱테스트")
                    .put("text", "전용앱 연결 테스트")
                    .put("ts", ""),
            )
```

을 교체:

```kotlin
            Uploader.testPost(Settings.firstRoom(), "앱테스트", "전용앱 연결 테스트")
```

`refreshInfo()`의 `append("방: ").append(Settings.roomName).append('\n')` 를 교체:

```kotlin
            append("대상 방: ").append(Settings.roomNamesList().joinToString(", ")).append('\n')
```

- [ ] **Step 3: 미사용 import 정리**

`MainActivity.kt` 상단에서 더 이상 직접 쓰지 않으면 `import org.json.JSONObject` 제거(테스트가 Uploader 경유로 바뀜).
빌드 경고만 나므로 필수는 아님 — 빌드가 unused import로 실패하지는 않는다.

- [ ] **Step 4: 전체 빌드 + 설치**

Run: `cd kakao-collector && ./install.sh`
Expected: `BUILD SUCCESSFUL` + `Installed on 1 device.`

- [ ] **Step 5: Task 3·5·6·7 통합 커밋**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/app/src/main/java/net/benelog/kakaocollector/Settings.kt \
  kakao-collector/app/src/main/java/net/benelog/kakaocollector/Uploader.kt \
  kakao-collector/app/src/main/java/net/benelog/kakaocollector/Poster.kt \
  kakao-collector/app/src/main/java/net/benelog/kakaocollector/KakaoCollectorService.kt \
  kakao-collector/app/src/main/java/net/benelog/kakaocollector/MainActivity.kt \
  kakao-collector/app/src/main/res/layout/activity_main.xml
git commit -m "멀티룸 매칭·activeRoom 태깅 + DB 기반 중복제거(재시작 후 재전송 방지)"
```

---

## Task 8: dump_db.sh + 문서 갱신

**Files:**
- Create: `kakao-collector/dump_db.sh`
- Modify: `kakao-collector/README.md`, `kakao-collector/TODO.md`

- [ ] **Step 1: dump_db.sh 작성**

`kakao-collector/dump_db.sh`:

```bash
#!/usr/bin/env bash
# 폰의 로컬 수집 DB(collector.db)를 꺼내 요약/최근 행을 보여준다.
set -e
ADB="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
PKG=net.benelog.kakaocollector
OUT=/tmp/collector.db

# run-as 로 앱 private DB를 바이너리 그대로 꺼냄(exec-out: CR/LF 변환 안 함).
"$ADB" exec-out run-as "$PKG" cat databases/collector.db > "$OUT" 2>/dev/null || {
  echo "DB를 못 꺼냄(아직 수집 0건이면 DB 미생성). 먼저 한 번 수집하세요."; exit 1; }

echo "== 방별 건수 / 미전송 =="
sqlite3 "$OUT" "select room, count(*) total, sum(case when sent_ok=0 then 1 else 0 end) unsent from messages group by room;"
echo
echo "== 최근 20건 (KST) =="
sqlite3 -header -column "$OUT" \
  "select datetime(collected_at/1000,'unixepoch','+9 hours') t, room, sender, sent_ok, substr(text,1,40) text from messages order by _id desc limit 20;"
```

- [ ] **Step 2: 실행권한 + 구문 확인**

Run: `cd kakao-collector && chmod +x dump_db.sh && bash -n dump_db.sh && echo OK`
Expected: `OK`

- [ ] **Step 3: README/TODO 갱신**

`README.md`에 멀티룸·로컬DB 설명 추가(설정 §1에 "대상 방 목록" 여러 줄, 동작 §7에 로컬 SQLite 영속/재시작 후 재전송 방지/`dump_db.sh` 조회). `TODO.md`의 "중복 상태 영속화" 항목을 `[x]`로, "멀티룸"을 반영. (구체 문구는 실제 파일 흐름에 맞춰 작성.)

- [ ] **Step 4: 커밋**

```bash
cd /home/benelog/source/benelog/hermes-modal
git add kakao-collector/dump_db.sh kakao-collector/README.md kakao-collector/TODO.md
git commit -m "dump_db.sh(로컬 DB 조회) + README/TODO 갱신"
```

---

## Task 9: 실기기 E2E 검증

**Files:** 없음(검증만).

- [ ] **Step 1: 설치 + 접근성 재바인딩 + calibrate=false 확인**

```bash
cd kakao-collector
./install.sh
./enable_service.sh
~/Android/Sdk/platform-tools/adb shell run-as net.benelog.kakaocollector grep -o 'calibrate[^/]*' shared_prefs/kakao_collector.xml
```
Expected: 설치 성공, `Bound`, `calibrate" value="false"`.

- [ ] **Step 2: 멀티룸 — 앱 설정에 방 2개 입력**

앱 "대상 방 목록"에 두 줄(예: `ABC(아카라카북클럽)` 과 다른 테스트 방 제목) 입력 후 "설정 저장".
확인: `adb shell run-as net.benelog.kakaocollector grep room_names shared_prefs/kakao_collector.xml` 에 두 줄이 보임.

- [ ] **Step 3: 각 방 수집 → DB의 room 태깅 확인**

각 방을 열고 스크롤(또는 `bash /tmp/watch_drive.sh` 류로 구동). 그 뒤:
```bash
cd kakao-collector && ./dump_db.sh
```
Expected: "방별 건수"에 **두 방이 각각** 나타나고, 메시지의 `room`이 해당 방으로 맞게 찍힘.
보낸이: 내 메시지→`정상혁`, 남 메시지→실제 닉네임.

- [ ] **Step 4: 영속화/재전송 방지 — 재시작 후 재스크롤**

1) 현재 Modal 건수 기록:
```bash
TOKEN="$(grep -E '^KAKAO_COLLECTOR_TOKEN=' "$HOME/.hermes/.env" | head -1 | cut -d= -f2-)"
curl -s "https://benelog--kakao-messages.modal.run?token=$TOKEN&since=1day" | python3 -c "import sys,json;print('before',json.load(sys.stdin).get('count'))"
```
2) 서비스 재시작: `cd kakao-collector && ./enable_service.sh`
3) 같은 방을 같은 구간으로 다시 스크롤.
4) 재확인:
```bash
curl -s "https://benelog--kakao-messages.modal.run?token=$TOKEN&since=1day" | python3 -c "import sys,json;print('after',json.load(sys.stdin).get('count'))"
~/Android/Sdk/platform-tools/adb logcat -d -s KakaoCollector | grep -c 'posted'
```
Expected: **before == after**(재전송으로 인한 신규 적재 0), DB 중복행 0(`dump_db.sh`의 방별 건수 불변).
(시드된 seen + DB UNIQUE 덕분에 재시작 후 같은 메시지는 POST 안 됨.)

- [ ] **Step 5: 단위테스트 최종 통과**

Run: `cd kakao-collector && JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem" ./gradlew testDebugUnitTest`
Expected: PASS(DedupeKey + RoomMatch).

- [ ] **Step 6: 검증 결과 요약 보고**

멀티룸 태깅·영속화·재전송방지·보낸이귀속이 모두 통과했는지 사용자에게 보고. 미흡 시 해당 Task로 회귀.

---

## 참고 / 주의

- **빌드는 JDK 17 필수**(전역 JDK 25면 `What went wrong: 25`). `./install.sh`가 자동 처리, gradle 직접 호출 시 `JAVA_HOME` 지정.
- **접근성은 재설치/재부팅 후 `./enable_service.sh`** (Android 13+ 제한된 설정 우회).
- **Modal 잔여 테스트 데이터**가 있으면 검증 전 사용자가 `modal dict clear kakao-collect -y`로 비우면 깔끔.
- Task 3·5·6·7은 서로 컴파일 의존(roomName/Poster 참조 교체)이라 **연속 수행 후 한 번에 빌드/커밋**하는 것을 권장(각 Task의 커밋 Step 참고).
