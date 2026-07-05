---
name: kakao-store-edit
description: Safely apply edits/deletions to collected KakaoTalk messages in the phone collector.db and/or the Modal kakao-collect Dict. Use whenever MUTATING either store (오수집 cleanup apply, row fixes). Read-only audits belong to the kakao-data-auditor agent instead.
---

# Kakao store edit (phone DB + Modal Dict)

Two independent stores; edits do NOT propagate between them. Summaries read ONLY
the Modal Dict. Always show the change plan and get user confirmation before
mutating — this is user data.

## 0. Backup first (both stores you will touch)

JSON-dump to the session scratchpad before any change. Non-negotiable.

## Phone DB (`net.benelog.kakaocollector`, `databases/collector.db`)

Resolve adb (may not be on PATH; same chain as `enable_service.sh`):
`ADB=$(command -v adb || echo "${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/platform-tools/adb")`
— use `$ADB` in the steps below. No sqlite3 CLI on host or device — use
python3 `sqlite3`. Table `messages`: `_id, room, sender, text, client_time
(YYYY-MM-DD), collected_at (epoch ms), sent_ok`.

1. Pull: `adb exec-out run-as net.benelog.kakaocollector cat databases/collector.db > local.db`
2. Backup, then edit `local.db` locally (address rows by `_id`).
3. Force-stop so the DB isn't open during replace: `adb shell am force-stop net.benelog.kakaocollector`
   (this disables the accessibility service — step 6 is therefore MANDATORY).
4. Push back:
   - `adb push local.db /data/local/tmp/x`
   - `adb shell run-as net.benelog.kakaocollector cp /data/local/tmp/x databases/collector.db`
   - GOTCHA: run `run-as … cp` DIRECTLY. Never wrap it in `sh -c '…'` — the
     wrapper silently drops the path argument.
   - Remove stale `collector.db-wal` / `collector.db-shm` the same way if present.
5. Sanity-check: re-pull and verify the edited rows.
6. MANDATORY recovery: `kakao-collector/enable_service.sh`, then verify
   `adb shell dumpsys accessibility | grep "label=Kakao Collector"` (rebind can
   lag seconds; re-entering the room kicks a scrape). Skipping this leaves
   collection silently dead.

## Modal Dict (`kakao-collect`)

- System python3 has no `modal` — use the pipx venv python, resolved via
  `MODAL_PY="$(pipx environment --value PIPX_LOCAL_VENVS)/modal/bin/python"`
  (equivalently: the shebang line of `command -v modal`). Then
  `modal.Dict.from_name("kakao-collect")`.
- Edit values IN PLACE under the EXISTING key — never re-key. Reads are
  key-agnostic, so a key that no longer matches `message_key` of the edited
  text is fine. Delete with `del d[key]`.
- Reuse `scripts/kakao/collector_core.py` (`clean_text`, `extends`,
  `message_key`) instead of reimplementing merge/normalize logic.
- Retention is 14 days on `received_at`; don't bother fixing rows about to expire.

## After both

Re-run the audit query that motivated the edit and confirm the finding is gone.
