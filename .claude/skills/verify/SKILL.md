---
name: verify
description: Verify the KakaoTalk collection pipeline end-to-end on the real device + Modal after changing kakao-collector/ or the kakao server path (modal_app.py, scripts/kakao/). Drives upload, capture, and (optionally) summary paths — not just unit tests.
---

# Verify collection pipeline end-to-end

Resolve adb first (may not be on PATH):
`ADB=$(command -v adb || echo "${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb")`.
Prereqs: device connected (`$ADB get-state` → `device`); a11y service bound
(`$ADB shell dumpsys accessibility | grep "label=Kakao Collector"` — if empty
run `kakao-collector/enable_service.sh`).
If app code changed, install first (android-build agent handles install +
re-enable). Token for curl: `adb shell run-as net.benelog.kakaocollector cat
shared_prefs/kakao_collector.xml`.

## 1. Unit layers (fail fast, no device)

- Server: `python3 -m unittest discover -s tests`
- Android: `cd kakao-collector && ./install.sh testDebugUnitTest`

## 2. Upload path (app → Modal)

Tap the main screen button "저장 후 테스트 메시지 전송" (launch MainActivity,
find `btnTest` bounds via `uiautomator dump`, `input tap`). It posts sender
`앱테스트` to the first target room. Confirm server-side:
`curl "https://benelog--kakao-messages.modal.run?token=<tok>&since=1day"` and
grep `앱테스트`.

## 3. Capture path (KakaoTalk → phone DB → Modal)

1. `adb shell logcat -c`
2. `adb shell monkey -p com.kakao.talk 1` — resumes the last room; entering a
   target room fires WINDOW_STATE_CHANGED and a scrape.
3. Expect `posted N new message(s)` on logcat TAG `KakaoCollector`. It logs only
   on NEW collections — re-reading already-collected messages is deduped
   silently, so scroll to genuinely new/uncollected messages when testing.
4. Confirm the phone DB row count rose and new rows have `sent_ok=1`
   (pull via `run-as cat databases/collector.db`, count with python3 sqlite3).

## 4. Summary path (only if summarize/prompt code changed)

Main screen "지금 요약 테스트" shows the summary in-app WITHOUT sending to the
room (safe). Hermes cold start can take tens of seconds. Alternatively POST
`https://benelog--kakao-summarize.modal.run?token=<tok>` with
`{"room": "<room>", "command": "요약"}`.

## 5. UI changes

Drive via `uiautomator dump` + `input tap`/`input swipe`. Activities other than
MainActivity are `exported=false` — you cannot `am start` them from shell; go
through the real button path.

Report per path: verified / skipped-and-why. Never claim success from unit
tests alone when the runtime surface changed.
