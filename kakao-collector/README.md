# Kakao Collector

카카오톡 특정 단일 방의 메시지를 모아 매일/온디맨드로 **요약**해 받기 위한 시스템에서,
**폰 쪽 수집기(Android 전용 앱)** 부분이다. 이 디렉토리는 그 앱이지만, README는 앱이 속한
**전체 통신 구조·요구사항·추구 원리**까지 함께 기록한다(다음 세션의 단일 진입점).

- 대상 방: **'아카라카북클럽'** (단일 방만 수집)
- 앱 역할: 대상 방 화면을 **접근성으로 읽어** `{room, sender, text, ts}`를 Modal `/ingest`로 POST. 수집은 **읽기 전용**.
- 요약/전달: 서버(Modal + Hermes)가 담당 — 이 앱과 분리됨.
- (선택) **방 멘션 요약 발신**: 방에서 `@정상혁 …요약` 멘션이 보이면 Modal `kakao_summarize`(Hermes LLM)로
  요약을 받아 **그 방으로 발신**한다. 읽기 전용을 깨는 부분이라 **기본 OFF**, 캘리브레이션 후 켠다. → §9.

---

## 1. 목표 / 요구사항

- 카카오톡 '아카라카북클럽' 방의 하루치 메시지를 모아, **Telegram**에서
  "카카오톡 ABC방 요약해줘"라고 보내면 최근(기본 24h) 대화를 **한국어로 요약**해 Telegram으로 받는다.
- 요약·전달은 다른 기능들처럼 **Modal**에서 실행한다.
- **카카오톡은 읽기 전용**으로만 다룬다(발신 안 함 = 리스크 최소).
- **조용한 방(알림 끔)도 수집**되어야 한다 → 알림이 아니라 **화면을 직접 읽는 접근성** 방식이 필수.
- 1차 범위는 **온디맨드(Telegram 트리거)**. 매일 07:00 cron 자동요약은 충분히 테스트 후 추가(범위 밖).

## 2. 전체 통신 구조

```
[폰: Kakao Collector 앱]  대상 방이 화면에 있을 때(사용자가 열어 스크롤) 접근성으로 말풍선 읽기
   │  {room, sender, text, ts}
   ▼  HTTPS POST  /ingest?token=...
[Modal] kakao_ingest(POST)  → modal.Dict "kakao-collect"에 중복제거 적재
        kakao_messages(GET) → since 기간 메시지 JSON 반환 (14일 보존, 읽을 때 정리)
   ▲  curl GET /messages?token=...&since=1day
[Modal: 기존 Telegram gateway = Hermes]
        Telegram "카카오톡 ABC방 요약해줘" → skill 'kakao-room-summary'가
        kakao_fetch.py로 /messages 조회 → 한국어 요약 → Telegram 답장
```

- 트리거·전달은 전부 **기존 Telegram gateway**로 처리(“Modal→폰 푸시 불가” 문제를 이렇게 우회).
- 그래서 폰 앱은 **수집(업로드)만** 하면 되고, 요약 요청/응답은 신경 쓸 필요 없음.

## 3. 추구하는 원리 / 설계 결정

- **공식 API 부재가 모든 것을 결정**: 카카오톡은 친구/그룹 방 메시지를 서버가 읽는 공식 API가 없다.
  따라서 캡처는 비공식이며, **메시지 도착 시점에 온라인인 사용자 기기**가 필요하다.
  Modal은 scale-to-zero라 캡처 지점이 될 수 없어 **폰 ↔ Modal 분리**가 강제됨.
- **조용한 방 = 접근성만 가능**: 알림 기반(MacroDroid 알림/IFTTT/Tasker AutoNotification 등)은
  muted 방에서 알림이 안 떠 못 잡는다. 화면을 읽는 **AccessibilityService**만이 조용한 방을 본다.
- **전용 앱을 택한 이유**: "앱마켓 정식 + 조용한 방 + 무료"를 동시에 만족하는 길은 없었다
  (Tasker+AutoInput=AutoInput 유료, MacroDroid=알림이라 muted 불가, AutoJs6=사이드로딩+범용엔진 신뢰 이슈).
  **내가 짠 단일 목적 앱**은 범용 스크립트 엔진이 아니라 신뢰가 명확하고, 무료이며, 접근성으로 조용한 방도 된다.
  (개인용이라 Play 스토어 미등록 + 내 서명 APK 사이드로딩으로 충분)
- **읽기 전용 원칙**: 접근성 서비스를 `com.kakao.talk`에만 바인딩하고, 카톡에 발신하지 않는다.
  LOCO 같은 비정상 클라이언트 신호가 없어 ToS 리스크가 가장 낮은 구성.
- **“열면 캡처” 모델**: 자동 스크롤 없이, 사용자가 방을 열어 스크롤하는 동안 보이는 것만 수집(best-effort).
  안 열면 수집 안 됨. 단순하고 안전(자동 조작 최소).
- **`since`는 수집 시각(received_at) 기준**: 디바이스가 절대 시각을 신뢰성 있게 못 만들기 때문.
  → 오늘 처음 스크롤한 오래된 메시지도 "오늘" 요약에 포함될 수 있음(= "지난 N일 **수집분**").
- **중복 제거는 양쪽**: 앱(스크롤 중 재노출)과 서버(같은 sender+text+time → 1건) 모두에서 거른다.

## 4. 핵심 레퍼런스 값

- 워크스페이스 `benelog`, Modal app `hermes-telegram-gateway`.
- 디바이스 POST: `POST https://benelog--kakao-ingest.modal.run?token=<TOKEN>`
  body `{"room":"아카라카북클럽","sender":"...","text":"...","ts":"오후 3:25"}`
- 조회: `GET https://benelog--kakao-messages.modal.run?token=<TOKEN>&since=1day[&room=...]`
- 저장소 `modal.Dict "kakao-collect"`, dedupe key = `room|sha1(room\x01sender\x01text\x01client_time)`, 보존 14일.
- 대상 방: **'아카라카북클럽' 1개만**.
- 토큰 위치: 로컬 `~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN` + Modal 시크릿 `hermes-modal-secrets` (**git에는 없음**).
- Telegram DM chat_id: `7160469912`.

## 5. 현재 상태 (2026-06-20)

- **서버측 완료**: 코드+단위테스트(25개 통과), `modal deploy` 완료, 엔드포인트 라이브,
  토큰 시크릿 반영, 라이브검증(401/적재/중복제거/조회) 완료, 저장소 비워둠.
  관련 코드: `scripts/kakao/collector_core.py`, `modal_app.py`(kakao_ingest/kakao_messages),
  `scripts/cron/kakao_fetch.py`, `scripts/skills/knowledge-base/kakao-room-summary/SKILL.md`.
  설계/계획: `docs/superpowers/specs|plans/2026-06-20-kakao-bookclub-summary-bot-*.md`.
- **이 앱(폰 수집기)**: **빌드·설치·실기기 수집까지 검증 완료** (Pixel 10 Pro XL, Android 16).
  실제 메시지가 올바른 보낸이·본문으로 Modal에 적재됨을 `/messages`로 확인.
  - 설정 코드-외부화: 토큰/URL/방/ids/CALIBRATE를 앱 "설정"에서 편집(`SharedPreferences`).
  - **실측 node id**(2026-06 카톡): 본문 `id/message`, 보낸이 **`id/nickname`**, 시각 `id/time`(희소), 방제목 `id/name`.
    → `Config.kt` 기본값에 반영됨.
  - **방 매칭 = 래치 방식**: 카톡은 방 제목을 '방 열 때'만 트리에 노출(스크롤 중엔 빠짐) → 입장 시 래치 ON,
    '다른 방 제목'이 보일 때만 OFF. 반응팝업/IME 등 일시 윈도우엔 래치 유지.
  - **보낸이 미상 메시지는 건너뜀**: 닉네임이 화면 밖이면 빈 sender로 중복 적재되므로 스킵.
  - **내 메시지 vs 남 메시지 = 정렬로 판별**: 카톡은 내가 보낸 메시지엔 닉네임을 안 띄운다. 말풍선이
    **우측 정렬(오른쪽 여백 < 왼쪽 여백)** 이면 내 메시지 → 보낸이를 설정의 "내 닉네임"으로,
    좌측 정렬이면 남 메시지 → 그 닉네임으로 적는다. 텍스트·문서(PDF)카드 모두 검증됨.
  - **URL 미리보기 카드는 수집 안 됨**: 미리보기는 `id/chat_forward`의 `id=null` 노드(제목/설명/도메인)라
    `id/message`가 아니어서 자동 제외된다.
  - **실기기 E2E 검증(2026-06-20)**: 내 메시지→`정상혁`, 남 메시지→실제 닉네임으로 적재 확인. 멀티룸(방별 태깅)·
    로컬 SQLite 영속·재시작 후 재전송방지·방별 구분(나와의 채팅 미수집)까지 확인(ABC 100여 건 수집).
  - **⚠ 카톡 접근성 출력 특성(중요·실측)**: 카톡이 값을 `text`가 아니라 **`contentDescription`** 에 두기도 한다.
    ① 방 제목은 `toolbar_default_title_text`의 **cd**(스크롤 중에도 안정적, 방 식별의 1차 수단).
    ② 말풍선 본문/닉네임도 `text`가 비고 **cd**에 옴 → 코드가 `text 우선, 없으면 cd`(`nodeValue`)로 읽는다.
    ③ 말풍선 RecyclerView가 `rootInActiveWindow`가 아닌 **다른 윈도우**에 있을 수 있어 `getWindows()` 전체를 훑는다.
    ④ 답장 인용은 `'Replied/Original message …'`로 와서 중복이라 건너뛴다. (카톡 UI 업데이트 시 재점검 지점.)
- 참고로 남겨둔 다른 디바이스 방식 가이드: `scripts/tasker/README.md`(Tasker+AutoInput),
  `scripts/autojs/README.md`(AutoJs6) — 채택은 이 전용 앱.

---

## 6. 앱 빌드 / Calibration / 설치 / 수집

요구사항: JDK 17, Android SDK(platform-34, build-tools 34.0.0), Android 8.0(API 26)+ 기기.

### 0) 빌드 환경 (이 머신에선 이미 구성됨)
- Android SDK: `~/Android/Sdk` (cmdline-tools + `platform-tools` + `platforms;android-34` + `build-tools;34.0.0`).
  `app` 모듈 기준 `local.properties`의 `sdk.dir`가 이를 가리킴(미커밋).
- JDK 17: `~/.sdkman/candidates/java/17.0.16-tem` (전역 기본 JDK는 25라 빌드 시 JDK 17 지정 필요).
- Gradle wrapper 8.7: `gradle-wrapper.jar`은 미커밋이라 최초 1회 생성됨(`gradle wrapper --gradle-version 8.7`).
- 새 머신이라면: cmdline-tools 받아 `sdkmanager`로 위 패키지 설치 → `local.properties`에 `sdk.dir` → wrapper 생성.

### 1) 설정 (앱 화면에서 입력 — 코드 수정 불필요)
앱 실행 → **"설정"** 섹션에서 입력 후 **"설정 저장"**:
- **토큰** = `~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN`과 동일 값. (SharedPreferences에 저장 → git에 안 남음.)
- **Modal Ingest URL** = 기본값 채워져 있음(필요시 수정).
- **대상 방 목록** = 방 제목을 한 줄에 하나씩 입력(여러 방 가능). 서비스는 열린 방이 목록에 있으면 그 방 이름을 태그해 전송.
- **내 닉네임** = 내가 보낸 메시지엔 카톡이 닉네임을 안 띄우므로, 내 메시지의 보낸이로 채울 값(예: `정상혁`).
  멀티프로필로 보는 사람마다 다르게 보여도 수집은 이 값으로 고정된다. **비워두면 내 메시지는 수집 안 됨.**
- **본문/보낸이/시각/방제목 id** = 아래 Calibration으로 확정.
- **CALIBRATE** 체크박스 = 켜면 수집 대신 화면 노드 id를 Logcat에 덤프.

> `Config.kt`는 이제 "기본값(폴백)"만 보유. 설정 화면에서 한 번 저장하면 그 값이 우선한다.
> 실값(토큰 등)을 코드에 넣을 필요가 없다.

### 2) Calibration (resource-id 확정 — 최초 1회 필수, 앱 재빌드 불필요)
1. 앱 "설정"에서 **CALIBRATE 체크 → 저장**.
2. 설정 → 접근성 → **Kakao Collector** 켜기(앱의 "접근성 설정 열기" 버튼).
3. 카톡에서 **대상 방을 연다**.
4. `adb logcat -s KakaoCollector` → `id=... text=...` 줄 확인.
5. 화면과 대조해 본문/보낸이/시각/방제목 id를 앱 "설정"의 각 필드에 입력.
6. **CALIBRATE 해제 → 저장**.

### 3) 빌드 / 설치
도우미 스크립트(권장 — 환경변수 자동 지정):
```bash
cd kakao-collector
./device_check.sh           # 폰 USB/디버깅 연결 진단 (no permissions·unauthorized 등 원인 안내)
./setup_udev.sh             # (Linux 최초 1회) adb udev 규칙 설치 — 'no permissions' 해결, sudo 필요
./install.sh                # 빌드+설치 (= assembleDebug + installDebug), JDK 17 자동 사용
./install.sh assembleDebug  # 설치 없이 APK만
./enable_service.sh         # 접근성 서비스 adb로 켜기 ('제한된 설정' 차단 우회, 재부팅 후 재활성)
./dump_db.sh                # 로컬 수집 DB 조회: 방별 건수·미전송·최근 행
```

> ⚠ **접근성 켜기가 막힐 때** (Android 13+): 사이드로딩 앱은 설정 UI에서 접근성 토글이 "제한된 설정"으로
> 막힌다. 두 가지 해법 — ① 설정 → 앱 → Kakao Collector → ⋮ → **"제한된 설정 허용"** 후 접근성에서 켜기,
> 또는 ② **`./enable_service.sh`** (adb로 직접 등록, 가장 확실). 재설치/재부팅 후 안 켜져 있으면 ②를 재실행.
수동으로 한다면:
```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.16-tem"   # ★ 빌드는 JDK 17
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # 폰 USB 연결(adb) 시 디바이스에 설치
```

> ⚠ **흔한 실패 2가지**
> 1. **`What went wrong: 25`** — 전역 기본 JDK가 25라 Gradle 8.7/AGP 8.5.2가 못 돎. → `JAVA_HOME`을 JDK 17로
>    지정하거나 `./install.sh` 사용(자동 처리).
> 2. **`no permissions (missing udev rules)`** — 리눅스에서 adb가 USB 노드 접근 불가. → `./setup_udev.sh` 후 폰 재연결.

(디버그 서명이면 개인 사이드로딩 충분. 빌드·설치 검증 완료 — Pixel 10 Pro XL에 설치 확인됨.)

### 4) 수집 / 확인
1. 접근성 ON 상태에서 **대상 방을 열고 위로 스크롤**.
2. 보이는 메시지가 자동으로 `/ingest`로 전송됨.
3. 확인: `curl -s "https://benelog--kakao-messages.modal.run?token=<토큰>&since=1day"`.
4. 연결만 빠르게 보려면 앱의 **"테스트 메시지 전송"** → `/messages` 확인 → 정리 `modal dict clear kakao-collect -y`.
5. E2E: Telegram 봇에게 "카카오톡 ABC방 요약해줘".

## 7. 동작 / 한계

- 읽기 전용(서비스가 `com.kakao.talk`로만 제한).
- "열면 캡처": 방 열어 스크롤한 범위만 수집(v1은 자동 스크롤 없음).
- 사진/스티커는 `[사진]` 등 텍스트로만.
- 연속 메시지는 직전 보낸이 승계.
- 카톡 UI 업데이트로 id가 바뀌면 Calibration 재실행.
- 중복은 클라이언트+서버 양쪽 제거.
- 수집 내역은 폰 로컬 SQLite(`collector.db`)에 영속 저장되며(보낸이/본문/방/시각/전송성공여부/수집시각),
  이 DB가 중복제거의 단일 출처라 **앱/서비스를 재시작해도 같은 메시지를 다시 전송하지 않는다**(서버는 그대로 멀티룸).
  오래된 행은 30일 후 정리.

## 8. 구성 파일
```
kakao-collector/
  settings.gradle.kts · build.gradle.kts · gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/
    build.gradle.kts · proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/java/net/benelog/kakaocollector/
      Config.kt                 설정 "기본값"(폴백). 실값은 Settings에서.
      Settings.kt               SharedPreferences 기반 런타임 설정(토큰/URL/방/ids/요약발신/CALIBRATE)
      CollectorApp.kt           Application — 시작 시 Settings 초기화
      KakaoCollectorService.kt  접근성 수집 서비스(핵심) — 수집 + 멘션 요약 트리거·발신
      SummaryTrigger.kt         멘션+요약 명령 판정(순수)
      Summarizer.kt             /summarize POST — Hermes LLM 요약 요청(서비스/액티비티 공용)
      Poster.kt                 /ingest POST — Settings.token/ingestUrl 사용
      MainActivity.kt           상태/접근성설정/설정편집/연결·요약 테스트 화면
    src/main/res/...            레이아웃·문자열·테마·접근성설정·아이콘
  TODO.md                       다음 세션 할 일
```

명령어 메모(서버측, 참고): 테스트 `python3 tests/test_*.py`, 배포 `modal deploy modal_app.py`,
시크릿 재생성 `python3 scripts/create_modal_secret.py`, 저장소 비우기 `modal dict clear kakao-collect -y`.

---

## 9. 방 멘션 요약 (발신) — 선택 기능

방에서 누군가 **`@정상혁`을 멘션하며 "요약해줘"** 라고 하면, 그 방의 최근 대화를 요약해 **그 방으로 답장**한다.
Telegram을 거치지 않고 카톡 방 안에서 끝난다. 요약 LLM은 새 키 없이 **Modal의 Hermes**(`hermes -z`)를 재사용한다.

### 흐름
1. 앱이 방의 **맨 아래(최신) 새 메시지**가 멘션 키워드+요약 키워드를 담고 있으면 트리거.
   (스크롤 백필로 올라온 옛 명령, 방 여는 순간의 옛 명령은 발화 안 함 — `CONTENT_CHANGED`+새 메시지일 때만.)
2. Modal `kakao_summarize`로 `{room, command}` POST → Hermes가 요약문 생성 → 반환.
3. 앱이 **발신 직전 현재 방==트리거 방을 재확인**하고, 입력창에 `ACTION_SET_TEXT`(마커+요약) → 전송버튼 클릭.

### 설정 (앱 "방 멘션 요약" 섹션)
- **Modal Summarize URL** = 기본값 채워져 있음(`modal deploy` 출력의 `kakao-summarize` URL과 일치하는지 확인).
- **멘션 키워드** = 메시지에 이 문자열이 있으면 "나를 부른 것". 비우면 **내 닉네임**으로 폴백(예: `정상혁`).
- **요약 키워드** = 기본 `요약`. 멘션 키워드와 함께 있어야 트리거.
- **입력창 id / 전송버튼 id** = 아래 캘리브레이션으로 확정. 비면 발신 안 함.
- **자동발신 체크박스** = **기본 OFF**. 캘리브레이션 후 켜야 실제로 방에 발신한다.

### 캘리브레이션 (입력창/전송버튼 id — 1회)
1. 앱 "설정"에서 **CALIBRATE 체크 → 저장**, 접근성 ON.
2. 대상 방을 열고 **입력창을 탭**한 뒤 `adb logcat -s KakaoCollector`에서 입력창 EditText의 `id=...` 확인.
3. 무언가 입력해 **전송 버튼이 나타난 상태**에서 전송 버튼의 `id=...` 확인.
4. 두 id를 앱 "입력창 id"/"전송버튼 id"에 입력, **CALIBRATE 해제 → 저장**.
   (실측 2026-06 카톡 = 코드 기본값: 입력창 `id/message_edit_text`(MultiAutoCompleteTextView),
   전송 `id/send_button_layout`. UI 업데이트로 바뀌면 위 절차로 재캘리브레이션.)
   > 참고: `uiautomator dump`(adb)로 화면 XML을 받아 resource-id를 찾는 방법이 가장 깔끔하다 —
   > 입력칸+전송버튼이 동시에 보이는 상태에서 `adb shell uiautomator dump` → XML에서 위 id 확인.

### 안전장치
- **자동발신 기본 OFF** — 켜기 전까지 어떤 방에도 발신하지 않는다.
- **루프 방지** — 봇 발신엔 마커(`🤖`) 접두, 마커 든 메시지는 트리거에서 제외.
- **오발신 방지** — 발신 직전 방 일치 재확인, 방별 동시요약 1건, 최신(맨 아래) 새 메시지만 명령 인정.
- 트리거는 **누구나** 가능(요청 사양). 특정인만으로 좁히려면 추후 멘션 키워드/화이트리스트 확장.

### 테스트
- **발신과 분리 검증**: 앱의 **"지금 요약 테스트"** 버튼 → 첫 방 요약을 받아 **앱 화면에 표시**(방에 발신 안 함).
  Modal/Hermes 경로가 정상인지 먼저 확인한 뒤 자동발신을 켠다.
- **E2E**: 자동발신 ON → 방에서 "@정상혁 요약해줘"(또는 "@정상혁 3일치 요약") → 그 방에 요약 답장.
