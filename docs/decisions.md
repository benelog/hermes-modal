# Decisions currently in effect

Architecture/design decisions as applied on `main` (2026-07-05). Superseded design
history lives only in git; platform facts behind these choices: [research.md](research.md).

## Pipeline

1. **Device-scrape → Modal store → Hermes summarize.** Android accessibility app (`kakao-collector/`) scrapes target rooms read-only and POSTs each message to `kakao_ingest`; no server-side Kakao read path exists. Rejected: Modal-hosted Kakao client (ban risk), notification-listener bots (mute-blind), remote-kakao (always-on UDP server mismatch).
2. **Store = `modal.Dict("kakao-collect")`**, 14-day retention pruned on read. Record: `{room, sender, text, client_time, received_at}`. Summaries/exports read ONLY this Dict — deleting phone rows changes nothing server-side. Rejected: modal.Volume (cross-container staleness).
3. **Summarize reuses resident Hermes** (`hermes -z` subprocess in `kakao_summarize`), capped at the 600 most recent messages; shared URLs appended deterministically (`extract_urls`) so the LLM can't drop them. Period parsed from the natural-language command (`extract_since`, default `1day`).
4. **Auth = shared token** (`KAKAO_COLLECTOR_TOKEN`) as query param on all kakao endpoints. Real values (token/URLs) live in app SharedPreferences + Modal Secret only, never in git.

## Scraping (2-pass, date-anchored)

5. **`client_time` holds a DATE (`YYYY-MM-DD`), not minute time** — KakaoTalk exposes no minute clock to a11y; the transient day-marker overlay is read during scroll instead. PASS 1 collects bubbles/nick/date markers with Y coords; PASS 2 assigns each message its nearest date (`DateAssigner`) and sender (`SenderAssigner`) above by Y.
6. **Dedupe key is sender-FREE: `(room, text, client_time)`** — phone `DedupeKey` and server `message_key` alike. Sender attribution is a heuristic that flips between scrapes; with sender in the key every flip created a duplicate. Accepted side effect: identical short text from different people on the same day collapses to one row.
7. **Sender attribution = nearest real nickname above by Y; skip if undeterminable** (collected later when the nickname is visible). No carry-forward of "last seen sender" (that was the dominant 오수집 cause). Own bubbles detected by geometry only (`SenderClassifier`: left ≥ 0.25·screenW AND right-margin < left-margin) → `ownName`; reply-header labels excluded; `답장 메시지 ` prefix stripped server-side (`clean_text`).
8. **Truncation/edit variants merge in place** (`KakaoText.extends` on phone, `plan_ingest` on server): keep earliest position/`received_at` (conversation order) and fullest text — no duplicate row for 꼬리 달기 edits.

## Device app

9. **Local SQLite `MessageStore` is the phone-side dedupe source of truth** (survives restarts, audit trail via `dump_db.sh`). `sent_ok=0` rows (failed uploads) are NOT retried — they age out; the only re-ingest path is server-side.
10. **Multi-room**: newline-separated room-title list, title match, single global `ownName`. No per-room config.
11. **Mention write-back** (the one deliberate break from read-only): when `{mention keyword + summary keyword}` appears, the device fetches a summary and types/sends it into that room. Safeguards: `auto_reply` OFF until input/send ids are calibrated; bot-marker prefix excluded from triggers (loop prevention); current room re-verified immediately before send; one in-flight summary per room.
12. **Settings UI is two screens**: main = frequently-touched (rooms, auto-reply, tests); everything set-once (token/URLs/ids/keywords/CALIBRATE) behind a 고급 설정 screen.

## Ops

13. **`modal deploy` is a production action** (live Telegram/Kakao bot) — requires explicit user confirmation; enforced by a PreToolUse hook.
14. **After any install/force-stop of the collector app, re-run `enable_service.sh`** and verify the a11y binding — package updates silently disable the service; enforced by a PostToolUse hook.
15. **BAND weekly bookclub posting: NOT active.** Designed + implemented on branch `kakao-bookclub-band` (watermark on `received_at`, Tue/Sat cron, post/create) but never merged to `main`.
