# Kakao Collector — 전용 수집 앱 (Android, AccessibilityService)

카카오톡 '아카라카북클럽' 방의 메시지를 **접근성으로 화면을 읽어** Modal `/ingest`로 POST하는
**단일 목적** 앱이다. 카톡에는 아무것도 보내지 않는다(읽기 전용). **조용한 방(알림 끔)도 동작**한다.
범용 자동화 엔진이 아니라 이 일만 하는 내 코드라, 신뢰/유지보수가 명확하다.

- Modal 쪽은 변경 없음 — `{room, sender, text, ts}`를 `/ingest`로 POST만 한다.
- 동작 모델: **"열면 캡처"** — 대상 방을 열어 위로 스크롤하면 보이는 말풍선을 그때그때 수집(자동 스크롤 없음).

## 요구사항
- Android Studio (JDK 17 포함, 최신 버전 권장)
- 안드로이드 기기/에뮬레이터: **Android 8.0(API 26) 이상**

## 0) Gradle wrapper (최초 1회)
`gradle-wrapper.jar`(바이너리)는 커밋하지 않는다. 둘 중 하나로 생성:
- **Android Studio로 `android/` 폴더 열기** → 첫 Gradle 동기화 시 자동 생성됨, 또는
- 로컬 Gradle이 있으면: `cd android && gradle wrapper --gradle-version 8.7`

## 1) 설정 (`app/src/main/java/net/benelog/kakaocollector/Config.kt`)
- `TOKEN` = Modal 시크릿 `KAKAO_COLLECTOR_TOKEN`과 동일한 값 (로컬 `~/.hermes/.env`에 있음)
- `ROOM_NAME` = 카톡 방 상단 표시명(이미 '아카라카북클럽')
- `INGEST_URL` = 이미 배포 주소로 고정
- `MSG_ID / NAME_ID / TIME_ID / TITLE_ID` = 아래 Calibration에서 확정
> ⚠️ 실제 `TOKEN` 값을 git에 커밋하지 말 것(개인 빌드에서만 채우고 공유 시 placeholder로 되돌리기).

## 2) Calibration (resource-id 확정 — 최초 1회 필수)
카톡 버전마다 노드 id가 다르므로 한 번 확인한다.
1. `Config.CALIBRATE = true` 로 두고 빌드·설치.
2. 설정 → 접근성 → **Kakao Collector** 켜기(앱의 "접근성 설정 열기" 버튼).
3. 카톡에서 **대상 방을 연다**.
4. PC에서 로그 확인:
   ```bash
   adb logcat -s KakaoCollector
   ```
   `id=... text=...` 줄들이 찍힌다.
5. 실제 화면과 대조해 id를 고른다:
   - 메시지 **본문** 줄의 id → `MSG_ID`
   - **보낸이 이름** 줄의 id → `NAME_ID`
   - **시각**(오후 3:25 등) 줄의 id → `TIME_ID`
   - 상단 **방 제목** 줄의 id → `TITLE_ID`
6. `Config.CALIBRATE = false` 로 되돌리고 다시 빌드·설치.

## 3) 빌드 / 설치
- Android Studio에서 ▶ Run, 또는 기기 연결 후:
  ```bash
  cd android
  ./gradlew installDebug      # adb로 디버그 APK 설치
  # 또는 APK만:
  ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
  ```
- 디버그 서명이면 개인 사이드로딩에 충분하다.

## 4) 수집 / 확인
1. 접근성 서비스 ON 상태에서 **대상 방을 열고 위로 스크롤**한다(하루치 범위까지).
2. 보이는 메시지가 자동으로 `/ingest`로 전송된다.
3. 적재 확인:
   ```bash
   curl -s "https://benelog--kakao-messages.modal.run?token=<토큰>&since=1day"
   ```
4. 연결만 빠르게 보려면 앱의 **"테스트 메시지 전송"** 버튼 → `/messages`에서 확인 →
   정리: `modal dict clear kakao-collect -y`
5. E2E: Telegram 봇에게 "카카오톡 ABC방 요약해줘".

## 동작 / 한계
- **읽기 전용**: 카톡에 발신하지 않음(접근성 서비스도 `com.kakao.talk`로만 제한).
- **열면 캡처**: 방을 열어 스크롤한 범위만 수집(자동 스크롤 없음 → v1은 사용자가 스크롤).
- 사진/스티커는 `[사진]` 등 텍스트로만.
- 같은 보낸이의 연속 메시지는 **직전 보낸이 승계**.
- 카톡 UI 업데이트로 id가 바뀌면 **Calibration 재실행**.
- 중복은 **클라이언트 + 서버** 양쪽에서 제거(같은 sender+text+time → 1건).
- 백그라운드 안정성(Doze/킬)은 "열면 캡처" 모델이라 크게 문제되지 않음.

## 향후(선택)
- 자동 스크롤(`ACTION_SCROLL_BACKWARD`)로 과거분 일괄 수집.
- 매일 일정 시각/방 열림에 트리거(전경 서비스 또는 알람).
- 설정 화면에서 토큰/방/ids 편집(현재는 `Config.kt` 상수).

## 구성 파일
```
android/
  settings.gradle.kts · build.gradle.kts · gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/
    build.gradle.kts · proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/java/net/benelog/kakaocollector/
      Config.kt              설정 상수(토큰/방/ids)
      KakaoCollectorService.kt  접근성 수집 서비스(핵심)
      Poster.kt              /ingest POST
      MainActivity.kt        상태/접근성설정/테스트 화면
    src/main/res/...         레이아웃·문자열·테마·접근성설정·아이콘
```
