# Kakao Collector — 멀티룸 + 로컬 영속 저장 설계

작성일: 2026-06-20. 대상: `kakao-collector/` 안드로이드 수집 앱.
선행 맥락: `kakao-collector/README.md`(전체 구조), `2026-06-20-kakao-bookclub-summary-bot-design.md`(서버 설계).

## 1. 목적 / 배경

현재 수집 앱은 (a) **단일 방**('ABC(아카라카북클럽)')만 대상으로 하고, (b) 중복 전송 방지용
`seen` 집합이 **인메모리**라 앱/서비스 재시작 시 사라진다. 재시작 후 같은 구간을 다시 스크롤하면
같은 메시지를 또 POST한다(서버가 중복제거하므로 저장은 안 늘지만 트래픽 낭비, 그리고 수집 이력을
로컬에서 확인할 방법이 없음).

이 설계는 두 가지를 추가한다:
1. **로컬 SQLite 영속 저장** — 수집 내역(감사/디버깅)을 폰에 기록하고, 그 DB를 중복제거의
   단일 출처로 삼아 **재시작해도 재전송하지 않게** 한다.
2. **멀티룸 지원** — 대상 방을 여러 개로 확장할 수 있게 한다(현재는 1개).

## 2. 조사로 확정된 전제 (변경 불필요 항목)

- **방 고유 ID 없음**: 카카오톡 접근성 트리에는 내부 채팅방 ID가 없다(노드는 레이아웃 id + 보이는
  텍스트 + 좌표뿐). 따라서 방 식별자는 **방 표시명(제목)** 을 사용한다. 방 이름이 바뀌면 연속성이
  끊기지만(서로 다른 방으로 취급) 이는 수용한다. (실측: `/tmp/struct*.log` 덤프에 id 후보 없음.)
- **Modal은 이미 멀티룸**: `scripts/kakao/collector_core.py`의 `message_key(room, sender, text,
  client_time)`가 `"{room}|{sha1(...)}"`로 **room별 키**이며, `select_messages(items, room, since,
  now)`가 `room` 필터를 지원한다. 즉 서버는 `room` 문자열만 다르면 여러 방을 그대로 저장/조회한다.
  **Modal/`modal_app.py` 변경 없음.**

## 3. 비목표 (이번 범위 밖)

- 수집 내역 **인앱 UI 화면**(adb로 DB 조회로 충분 — 사용자 선택).
- **전송 실패 자동 재시도 큐**(실패는 `sent_ok=0`으로 기록만; 재시도는 후속).
- Modal 저장구조/엔드포인트 변경.
- 방별로 다른 `ownName`(멀티프로필) — 내 닉네임은 **전역 1개**로 고정.

## 4. 설계

### 4.1 멀티룸 타게팅

- `Settings`: 단일 `roomName` → **`roomNames`**. 저장은 한 prefs 문자열에 **줄바꿈 구분**으로 보관하고,
  `roomNamesList(): List<String>`(trim, 빈 줄 제거)로 노출. 기본값/마이그레이션: `room_names` 키가
  없으면 기존 `room_name`(단일) 값으로 폴백 → 기존 설치가 그대로 동작.
- `MainActivity`/레이아웃: "대상 방 목록"(여러 줄 입력) 필드. 한 줄에 방 제목 하나.
- `KakaoCollectorService`:
  - 매칭을 목록 대상으로 일반화. 화면 제목(`visibleRoomTitle`)이 **목록 중 하나를 포함하면** 그 방으로
    입장 확정하고 `activeRoom = 매칭된 제목`을 기억한다.
  - 화면 전환 시 제목이 보이는데 **목록 어디에도 없으면** 래치 OFF(다른 방으로 나감).
  - 수집된 메시지의 `room` 필드는 하드코딩 대신 **`activeRoom`** 으로 태깅.
- `ownName`(내 닉네임), resource-id들, `CALIBRATE`는 전역 1개 유지(카톡 UI는 방과 무관하므로).

### 4.2 로컬 SQLite 저장 (`MessageStore`)

- `SQLiteOpenHelper`, DB `collector.db`, 테이블 `messages`:
  | 컬럼 | 타입 | 비고 |
  |---|---|---|
  | `_id` | INTEGER PK AUTOINCREMENT | |
  | `room` | TEXT NOT NULL | 수집 당시 활성 방 제목 |
  | `sender` | TEXT NOT NULL | 내 메시지면 ownName |
  | `text` | TEXT NOT NULL | 본문 |
  | `client_time` | TEXT | 카톡 표시 시각(희소, 빌 수 있음) |
  | `collected_at` | INTEGER NOT NULL | epoch millis(수집 시각) |
  | `sent_ok` | INTEGER NOT NULL DEFAULT 0 | 0=미전송/실패, 1=POST 200 |

  **UNIQUE(room, sender, text, client_time)** — 중복제거의 단일 출처.
- API:
  - `recordNew(room, sender, text, clientTime, nowMillis): Long?` — `INSERT ... ON CONFLICT IGNORE`.
    새 행이면 rowId, 중복이면 null.
  - `markSent(rowId)` — `sent_ok=1`.
  - `prune(cutoffMillis)` — `collected_at < cutoff` 삭제(용량 상한).
  - `recentKeys(limit): Set<String>` — 최근 행들의 dedupe 키(메모리 시드용).
- **dedupe 키(인메모리·DB 공통)는 서버 `message_key`와 동일하게 `room`을 포함**한다:
  `room ⌷ sender ⌷ text ⌷ client_time`(⌷ = U+0001). 멀티룸에서 같은 본문이 다른 방에서 와도 별개로 취급.
  DB UNIQUE도 같은 4컬럼.

### 4.3 중복제거 흐름 (인메모리 + DB 2단)

재시작 후 재전송을 막으려면 DB가 권위 있는 출처여야 하고, 스크롤마다 모든 말풍선을 DB로
질의하면 비싸므로 인메모리 캐시를 앞에 둔다.

1. **서비스 시작(`onServiceConnected`)**: `MessageStore.recentKeys(CAP)`로 최근 키를 인메모리
   `seen`에 시드한다(+ `prune` 1회). → 재시작해도 최근 수집분을 "이미 봄"으로 인식.
2. **스크랩(접근성 스레드)**: 메시지마다 키 계산. `seen`에 있으면 skip(빠른 경로). 없으면 `seen`에
   추가하고 백그라운드로 제출(`Uploader.submit(room, sender, text, ts)`).
3. **백그라운드(단일 executor)**:
   - `recordNew(...)` → 새 행이면 `Poster.post(...)` → 200이면 `markSent`. 중복(null)이면 아무것도 안 함.
   - DB가 최종 권위 → `seen`이 캡으로 밀려 키를 잊어도 DB가 재전송을 막는다.
- 기존 인메모리 전용 `remember()`/`SEEN_CAP`은 위 2단 방식으로 대체. DB 쓰기는 접근성 스레드 밖.
- 보존: 시작 시 `prune(now - 30일)`. (서버 14일보다 길게 잡아 감사 여유. 시드도 최근분만.)

### 4.4 조회(감사/디버깅)

- 인앱 UI 없음. 헬퍼 `kakao-collector/dump_db.sh`:
  - `adb exec-out run-as net.benelog.kakaocollector cat databases/collector.db > /tmp/collector.db`
  - `sqlite3 /tmp/collector.db "select datetime(collected_at/1000,'unixepoch','+9 hours') t, room, sender, sent_ok, substr(text,1,40) from messages order by _id desc limit 50;"`
  - 방별 건수, 미전송(`sent_ok=0`) 건수 요약도 출력.

## 5. 변경 범위 (파일)

- `Settings.kt` — `roomNames`(+`roomNamesList`), 마이그레이션, `save(...)` 시그니처에 roomNames.
- `Config.kt` — 기본 방 목록 주석.
- `MainActivity.kt` / `res/layout/activity_main.xml` — "대상 방 목록" 다중행 필드.
- `KakaoCollectorService.kt` — 목록 매칭/`activeRoom` 태깅, `remember()` → DB 2단 dedup, 시작 시 시드+prune.
- **신규** `MessageStore.kt` — SQLiteOpenHelper.
- `Poster.kt` → 제출 계층(`Uploader`)로 정리: 백그라운드에서 store→post→markSent.
- **신규** `kakao-collector/dump_db.sh`.
- Modal/서버: **변경 없음**.

## 6. 검증 (테스트)

앱에 자동 테스트 인프라가 없으므로 **실기기 검증**을 1차로 한다(기존에 확립한 adb 구동 방식):
- 멀티룸: 대상 목록에 2개 방을 넣고 각 방을 열어 스크롤 → DB의 `room`이 각각 맞게 찍히는지(`dump_db.sh`).
- 영속화: 수집 후 서비스 재시작(접근성 토글) → 같은 구간 재스크롤 → **재전송(POST) 0**, DB 중복행 0 확인
  (`sent_ok`/건수 변화 없음, Modal 건수 불변).
- 보낸이 귀속(기존): 내 메시지→ownName, 남 메시지→닉네임 유지.
- 순수 로직(키 생성, 목록 매칭, prune 컷오프)은 Android 비의존 함수로 빼낼 수 있으면 JVM 단위테스트 추가(선택).

## 7. 미해결/주의

- 방 제목 변경 시 이전 수집분과 다른 방으로 분리됨(수용). 사용자가 목록을 새 이름으로 갱신해야 함.
- `client_time`이 자주 비어 UNIQUE 키가 `(room,sender,text,"")`가 되는 경우: 같은 사람이 같은 본문을
  같은(빈) 시각으로 보낸 두 메시지는 1건으로 합쳐짐(드묾, 기존 서버 dedup도 동일 특성).
- 멀티프로필로 같은 사람이 방마다 다른 닉네임으로 보여도, 남 메시지는 보이는 닉네임 그대로 저장(방별로
  다를 수 있음). 내 메시지만 전역 ownName.
