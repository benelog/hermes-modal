# 디바이스 수집 셋업 (AutoJs6 + 아카라카북클럽)

카카오톡 '아카라카북클럽' 방의 메시지를 폰에서 긁어 Modal 수집 엔드포인트로 보낸다.
카톡에는 아무것도 보내지 않는다(읽기 전용). 조용한 방(알림 끔)도 동작한다.

## 준비물
- 안드로이드 폰(메인폰 가능). 카카오톡에 본인 계정 로그인, 대상 방 가입 상태.
- [AutoJs6](https://github.com/SuperMonster003/AutoJs6) 설치(무료, MPL-2.0).

## 설치
1. AutoJs6 설치 후 **접근성 권한**을 부여한다(설정 → 접근성 → AutoJs6 켜기).
2. 배터리 최적화에서 AutoJs6와 카카오톡을 제외한다(백그라운드 종료 방지).
3. `scripts/autojs/kakao_collect.js`를 폰의 AutoJs6 스크립트로 가져온다.
4. 스크립트 상단 `CONFIG`를 채운다:
   - `ROOM_NAME`: 채팅방 상단에 보이는 정확한 표시명.
   - `INGEST_URL`: `modal deploy` 출력의 kakao-ingest URL.
   - `TOKEN`: Modal 시크릿의 `KAKAO_COLLECTOR_TOKEN`과 동일한 값
     (`KAKAO_COLLECTOR_TOKEN=<원하는값> python scripts/create_modal_secret.py`로 값을 고정해두면 편하다).

## Calibration (선택자 보정 — 최초 1회 필수)
카톡 버전마다 노드 id가 다르다. 대상 방을 화면에 띄운 뒤, 스크립트 맨 아래를
`main();` 대신 `calibrate();`로 바꿔 한 번 실행한다. AutoJs6 로그에 찍힌
`[클래스] id=... text=...`를 보고 `CONFIG.SELECTOR`의 4개 id를 실제 값으로 맞춘다:
- `MESSAGE_TEXT_ID`: 말풍선 본문이 담긴 TextView의 id
- `SENDER_NAME_ID`: 보낸이 이름 TextView의 id
- `TIME_ID`: 시각(예: 오후 3:25) TextView의 id
- `TITLE_ID`: 상단 방 제목 노드의 id
보정 후 다시 `main();`으로 되돌린다.

## 수집 테스트
1. 대상 방을 연다.
2. 스크립트를 실행한다. 위로 자동 스크롤하며 수집하고, 끝나면 "수집 완료: N건" 토스트.
3. Modal에서 적재 확인:
   ```bash
   curl -s "<kakao-messages URL>?token=$TOKEN&since=1day"
   ```
   `count`와 `messages`가 화면에서 본 메시지와 맞는지 확인.

## 한계
- 방을 열어 스크롤한 범위만 수집된다("열면 캡처", best-effort). 안 열면 수집 안 됨.
- 사진/스티커는 `[사진]` 등 텍스트로만 잡힌다.
- 같은 보낸이의 연속 메시지는 보낸이가 승계 처리된다.
- 카톡 UI가 업데이트되면 calibration을 다시 해야 할 수 있다.
