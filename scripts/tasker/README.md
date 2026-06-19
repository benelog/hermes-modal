# 디바이스 수집 셋업 (Tasker + AutoInput · 앱마켓 정식 출시)

카카오톡 '아카라카북클럽' 방 메시지를 폰에서 긁어 Modal 수집 엔드포인트로 보낸다.
**조용한 방(알림 끔)도 동작**한다(접근성 기반). 카톡에는 아무것도 보내지 않는다(읽기 전용).
Modal 쪽은 변경 없음 — `{room, sender, text, ts}`를 `/ingest`로 POST만 한다.

> 솔직히: 이 방식은 Play 스토어 정식 앱(검증된 개발사)이라 사이드로딩이 없어 더 안전하지만,
> "화면을 읽는다"는 **접근성 권한**은 AutoJs6와 동일하게 필요하다(공식 API가 없어 불가피).
> 또한 Tasker 자동화 셋업은 노코드는 아니고 약간 손이 간다(특히 셀렉터 보정).

## 준비물 (모두 Play 스토어 정식)
- **Tasker** (유료)
- **AutoInput** (Tasker 플러그인, 유료) — 접근성으로 화면 읽기/스크롤
- 안드로이드 폰: 카카오톡 본인 로그인 + 대상 방 가입

## 권한
1. AutoInput 첫 실행 → 안내대로 **접근성 권한** 부여(설정 → 접근성 → AutoInput ON).
2. Tasker · AutoInput · 카카오톡을 **배터리 최적화에서 제외**(백그라운드 종료 방지).

## 설정 변수 (Tasker, 한 번만 — Task 맨 앞에 Variable Set으로 둬도 됨)
- `%ROOM` = `아카라카북클럽` (방 상단에 보이는 정확한 표시명)
- `%URL` = `https://benelog--kakao-ingest.modal.run`
- `%TOKEN` = `<KAKAO_COLLECTOR_TOKEN>` (Modal 시크릿과 동일한 값)
- `%IDMSG` / `%IDNAME` / `%IDTIME` = 메시지본문 / 보낸이 / 시각 노드의 resource-id
  (아래 Calibration에서 확인. 보통 `com.kakao.talk:id/...` 형태, 카톡 버전마다 다름)

## Calibration (셀렉터 확인 — 최초 1회 필수)
1. 카톡에서 **대상 방을 화면에 띄운다**.
2. AutoInput의 화면 요소 조회로 현재 화면의 (텍스트 + Id) 목록을 본다:
   - 간단한 방법: Tasker → 새 Task → Action `Plugin → AutoInput → UI Query`(필터 비움) 추가 →
     액션 편집 화면의 ▶(테스트 실행)로 결과 배열을 확인. 또는 AutoInput 앱 내 화면분석 도구 사용.
3. 목록에서 Id를 찾아 위 변수에 넣는다:
   - 메시지 **본문** 텍스트가 든 요소의 Id → `%IDMSG`
   - **보낸이 이름** 요소의 Id → `%IDNAME`
   - **시각**(예: 오후 3:25) 요소의 Id → `%IDTIME`

## Task 만들기: "KakaoCollect"
아래 순서대로 Action을 추가한다(괄호 안은 Tasker 액션 종류).

1. (Variable Set) `%SEEN` ← 비움
2. (Variable Set) `%ROOM`,`%URL`,`%TOKEN`,`%IDMSG`,`%IDNAME`,`%IDTIME` ← 위 값들 (이미 전역이면 생략)
3. **(For)** Variable `%i`  Items `1:40`   ← 최대 40회 스크롤(안전장치)
   아래 4~9를 For 블록 안에 둔다:
4. **(Plugin → AutoInput → UI Query)** 필터 없음 → 화면 요소가 `%aitext()`, `%aiid()` 배열로 채워짐
   (출력 변수 이름은 액션의 "Variables" 탭에서 확인 — 보통 `ai...` 접두어)
5. **(Code → JavaScriptlet)** 아래 코드 → `%PAYLOADS()`, `%NEWCOUNT`, `%SEEN` 생성/갱신
6. **(For)** Variable `%p`  Items `%PAYLOADS()`
7. ┗ **(Net → HTTP Request)** Method `POST` · URL `%URL?token=%TOKEN` ·
   Headers `Content-Type:application/json` · Body `%p`
8. **(Task → If)** `%NEWCOUNT ~ 0` **AND** `%i > 1`  →  **(Task → Goto)** 액션 10번(Flash)로 점프
   (= 더 이상 새 메시지가 없으면 맨 위 도달로 보고 종료)
9. **(Plugin → AutoInput → Action)** Type `Scroll` · 방향 `Up`(또는 Backward) · 대상=메시지 목록
   → 과거 메시지 로드. 이어서 **(Task → Wait)** `700ms`
10. (For 종료 후) **(Alert → Flash)** `수집 완료`

### 5번 JavaScriptlet 코드
```js
var seen = (typeof SEEN !== 'undefined' && SEEN) ? SEEN.split("\n") : [];
var set = {}; for (var s = 0; s < seen.length; s++) set[seen[s]] = true;
var curSender = "", curTime = "", payloads = [];
var n = (typeof aitext !== 'undefined' && aitext) ? aitext.length : 0;
for (var i = 0; i < n; i++) {
  var id = (aiid && aiid[i]) ? ("" + aiid[i]) : "";
  var tx = (aitext && aitext[i]) ? ("" + aitext[i]).trim() : "";
  if (!tx) continue;
  if (id.indexOf(IDNAME) >= 0) { curSender = tx; continue; }
  if (id.indexOf(IDTIME) >= 0) { curTime = tx; continue; }
  if (id.indexOf(IDMSG) >= 0) {
    var key = curSender + "" + tx + "" + curTime;
    if (set[key]) continue;
    set[key] = true; seen.push(key);
    payloads.push(JSON.stringify({ room: ROOM, sender: curSender, text: tx, ts: curTime }));
  }
}
var PAYLOADS = payloads;        // → Tasker %PAYLOADS() (JSON 문자열 배열)
var NEWCOUNT = payloads.length; // → %NEWCOUNT
var SEEN = seen.join("\n");     // → 갱신된 %SEEN (스크롤 간 중복 방지)
```
- JavaScriptlet은 Tasker 변수 `%ROOM/%IDMSG/%IDNAME/%IDTIME/%SEEN` 와 AutoInput 배열
  `%aitext()/%aiid()` 를 그대로 읽고, `%PAYLOADS()/%NEWCOUNT/%SEEN` 를 돌려준다.
- 변수명이 안 맞으면(버전차) AutoInput UI Query 액션의 출력 변수명을 확인해 `aitext/aiid`를 맞춘다.

## 실행 / 확인
1. 대상 방을 연다.
2. Task "KakaoCollect" 실행(Tasker에서 ▶ 또는 홈 위젯). 위로 자동 스크롤하며 전송 → "수집 완료" 플래시.
3. Modal 적재 확인:
   ```bash
   curl -s "https://benelog--kakao-messages.modal.run?token=<토큰>&since=1day"
   ```
   `count`/`messages`가 화면에서 본 것과 맞는지 확인.
4. Telegram 봇에게 "카카오톡 ABC방 요약해줘" → 한국어 요약 답장.

## 한계 / 팁
- 방을 **열어 스크롤한 범위만** 수집된다("열면 캡처", best-effort). 안 열면 수집 안 됨.
- 사진/스티커는 `[사진]` 등 텍스트로만 잡힌다.
- 같은 보낸이의 연속 메시지는 **직전 보낸이 승계**로 처리된다.
- 카톡 업데이트로 resource-id가 바뀌면 **Calibration 재실행**.
- 같은 메시지를 여러 번 긁어도 **서버에서 한 번 더 중복 제거**된다(같은 sender+text+time → 1건).
- 매일 자동화: Tasker Profile로 "특정 시각" 또는 "카카오톡 그 방 열림"을 트리거로 걸 수 있으나,
  우선은 **수동 실행으로 충분히 테스트**한 뒤 자동화하는 것을 권장.
