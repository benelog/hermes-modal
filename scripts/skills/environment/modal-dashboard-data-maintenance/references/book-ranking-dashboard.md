# Book ranking dashboard reference

Observed application details; verify these against the live deployment before reuse.

## Resource map

- Public page: https://benelog--book-rank.modal.run/
- Book: AI 에이전트와 스프링 배치를 활용한 배치 엔지니어링
- Kyobo product: https://product.kyobobook.co.kr/detail/S000221888579
- Modal Volume: `book-rank`
- Remote data object: `/history.json`
- Collector/parser source observed at `/opt/hermes-modal/scripts/book_rank.py`
- Dashboard uses Korean dates and advertises collection at 07:00 KST.

## Schema and semantics

Root object contains `records`, a list of objects with `date`, `fetched_at`, and `stores`. Store keys include `yes24`, `kyobo`, and `aladin`. Each store entry contains `category`, `rank`, `note`, and `error`; manual entries also carry `manual: true`.

For Kyobo, the observed category was `컴퓨터/IT`, note `주간`, and manual provenance was displayed as `수동`. Correct the target entry in place without touching the other stores or collection timestamp. If new manual provenance is needed, follow the current application's schema rather than inventing additional fields.

Relevant source helpers:

- `load_history` / `save_history`: JSON history persistence.
- `parse_manual_input`: accepts a rank, category + rank, optional ISO date, or no-ranking marker; returns a manual Kyobo entry.
- `upsert_record`: merges supplied stores into a date, preserving absent stores; failed collection does not replace an existing successful value. It does not guarantee a manual value survives a later successful scrape.
- `annotate`: computes deltas against earlier dates, not the replaced same-day value.

## Command sequence

Use unique local paths for each operation. These commands show the verified CLI form, not permission to overwrite without inspecting the latest data:

```bash
TZ=Asia/Seoul date
curl -fsSL https://benelog--book-rank.modal.run/
modal volume list
modal volume ls book-rank
modal volume get book-rank history.json /tmp/book-rank-before.json
```

Read the backup with the file reader, copy it to a separate working file, and use a targeted patch or validated transformation for only the selected date's Kyobo record. Validate that unrelated values remain unchanged. Check for concurrent updates before upload.

```bash
modal volume put --force book-rank /tmp/book-rank-updated.json /history.json
curl -fsSL https://benelog--book-rank.modal.run/
```

In the observed maintenance run, the public page displayed the corrected value immediately after Volume upload; no redeployment was necessary. Still fetch and verify on every run rather than assuming immediate visibility.
