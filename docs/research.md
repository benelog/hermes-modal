# Platform research findings

Durable facts discovered while building the KakaoTalk collector + Modal summarizer.
Distilled from the 2026-06 design/plan docs (removed) plus later device forensics.
Decisions built on these facts: see [decisions.md](decisions.md).

## Kakao APIs — why server-side capture is impossible

- No official API reads friend/group room messages: Message API is send-only; Channel/OpenBuilder bots only receive 1:1 utterances addressed to the channel and cannot join existing group rooms; KakaoWork bot API is send-only; conversation export (.txt) is a manual UI action.
- Capture therefore requires a user-owned device that is online, running the room UI. A Modal container cannot host a Kakao client: scale-to-zero can't keep a login resident, and container logins trip device-auth/LOCO abnormal-client detection (fast ban).
- Notification-listener bots (MessengerBot R, remote-kakao) capture nothing in muted rooms — no notification fires. Accessibility scraping is the only path for silent rooms.

## KakaoTalk accessibility tree (Pixel 10 Pro XL, Android 16, 2026-06 build)

- No internal chat-room id is exposed — only layout ids, visible text, coordinates. Room identity = visible title text; renaming a room breaks continuity.
- Message minute-time (오후 3:01) is NOT exposed to the a11y tree at all. Only the day marker `chat_log_recycler_date_indicator` ("2026. 06. 24. Wed") is available — and it is a transient sticky overlay: absent from static uiautomator dumps, catchable only during scroll events.
- Nickname appears only on the FIRST bubble of a consecutive same-sender run; later bubbles have no sender node. Attributing them to "the last nickname seen" mis-attributes when the run's head scrolled off-screen (this was the dominant 오수집 cause).
- Reply bubbles: `nickname` = the real replier; `message` text = `답장 메시지 {reply body}` (prefix strips to the true reply; body is never the quoted original); the quoted original lives in separate `source_nickname`/`source_message` nodes (id ≠ `message`, so not collected).
- Own bubbles carry no nickname/profile node. Geometry separates own vs others: others' bubbles start at left ratio ≈0.14; own at ≥0.34. During fast scroll, node bounds are UNSTABLE → geometry misfires; a settle-debounce (collect ~250ms after scroll stops) reduces but does not eliminate them.
- Resource ids (message/nickname/time/title/input/send) are version-dependent; must be re-calibrated after KakaoTalk updates (CALIBRATE mode dumps node ids to logcat).

## Android platform behaviors that silently kill collection

- `am force-stop` (or any package update, incl. `installDebug`) disables the accessibility service: the system clears `enabled_accessibility_services`. No error surfaces anywhere; collection just stops.
- Android 13+ "Restricted settings" blocks the a11y toggle for sideloaded apps. `enable_service.sh` bypasses via adb `settings put secure`. But without the `ACCESS_RESTRICTED_SETTINGS` appop allowed, the OS can refuse a REBIND later: observed 2026-07-04 — LMK killed the cached process, the rebind was rejected (appops rejectTime), and the system (`pkg:android`) nulled the setting.
- Forensic sources when "who disabled it": `dumpsys activity exit-info <pkg>` (death reasons + timestamps), `appops get <pkg> ACCESS_RESTRICTED_SETTINGS` (rejectTime), `dumpsys settings` (writer package per setting), `dumpsys usagestats query --package` (bucket timeline). logcat rotates within ~a day — useless for older incidents.
- After `enable_service.sh` the rebind can lag seconds; re-entering the room (WINDOW_STATE_CHANGED) reliably kicks a scrape.

## Modal platform

- `modal.Dict` over `modal.Volume` for the message store: ingest and query run in different containers; Volume commit/reload timing can hide fresh writes, Dict reads are always current. Per-message keys avoid read-modify-write contention.
- **`modal.Dict` entries expire on their own: "An individual Dict entry will expire after 7 days of inactivity (no reads or writes)"** (modal.com/docs/reference/modal.Dict; Dicts created before 2025-05-20 instead expire 30 days after last write and are memory-backed). Measured 2026-08-24 on `kakao-collect`: of 4881 phone rows, every one collected on/after 08-16 was present server-side (369/369), while only 8 of the 572 collected 08-11~08-15 survived — and all 8 are entries that were later re-written in place (stored key ≠ recomputed key), i.e. the survivors are exactly the ones that saw a write. The app's own 14-day `expired_keys` prune is therefore not the binding retention; whole-dict `list(kakao_dict.items())` scans on ingest/read did not keep untouched entries alive.
- `cron_tick` (07:00/19:00 KST) wakes a container to run `hermes cron tick`, keeping the gateway scale-to-zero; the hermes-home volume is committed at end of each tick, so mid-tick file writes persist (usable as watermarks).
- `hermes -z PROMPT` one-shot prints only the final LLM text to stdout — a Modal endpoint can reuse the resident Hermes model as a subprocess summarizer, no extra API key.

## Naver BAND API (feature currently parked on branch `kakao-bookclub-band`)

- `POST openapi.band.us/v2.2/band/post/create` (form: access_token, band_key, content, do_push); success = `result_code == 1`.
- `band_key` ≠ the numeric id in the band URL; fetch once via `GET /v2.1/bands`.
- Access token from the dev-console "밴드 계정 연동" button, lifetime ≈10 years — no refresh flow needed for cron. Plain-text URLs auto-link; `do_push=false` suppresses member pushes; post length limit undocumented → cap conservatively.
