---
name: sync-qmd
description: Pull the six QMD-backed git repos (blog, wiki, devnote, bookshelf, bookshelf-it, diary) up to date and refresh the QMD index so search reflects the latest content.
---

# Sync QMD

## When to use

- The user asks to sync, refresh, update, or "pull" QMD knowledge sources, or says e.g. "qmd 갱신", "최신 내용 가져와".
- A recently-written diary entry, blog post, wiki page, or bookshelf note is not yet appearing in QMD search results.
- Search results look stale relative to what the user wrote upstream.

## Repos

All six collections live under `/root/workspace/benelog/<name>/` and track the `master` branch. The QMD index name is `index`.

| name | collection_path |
|---|---|
| blog | src/content |
| wiki | content |
| devnote | content |
| bookshelf | content |
| bookshelf-it | content |
| diary | content |

## Procedure

1. Fetch and fast-forward each repo:
   ```
   for r in blog wiki devnote bookshelf bookshelf-it diary; do
     echo "=== $r ==="
     git -C /root/workspace/benelog/$r fetch --prune origin
     git -C /root/workspace/benelog/$r pull --ff-only origin master
   done
   ```
   Note which repos reported "Already up to date" vs new commits — useful for the summary.

2. Update the QMD index:
   ```
   qmd --index index update
   ```

3. Sanity-check:
   ```
   qmd --index index status
   ```

4. Embeddings (optional, heavy):
   - Only run when the user explicitly asks for embedding refresh, or when `qmd status` shows a non-trivial `needsEmbedding` count.
   - `qmd --index index embed` — embed only docs that need it.
   - `qmd --index index embed -f` — re-embed everything; slow on CPU, only on explicit request.
   - In Hermes, run long embedding jobs as tracked background processes with completion notification:
     ```
     qmd --index index embed
     ```
   - If `qmd embed` exits 0 but reports `⚠ N chunks failed` and `qmd status` still shows Pending, retry with small batches to reduce CPU/resource-related batch failures:
     ```
     qmd --index index embed --max-docs-per-batch 1 --max-batch-mb 1
     ```
     This has successfully reduced QMD pending embeddings from 120 to 31 in the Modal CPU-only environment.

5. Embedding progress checks:
   - Prefer direct CLI status over MCP status for embedding progress:
     ```
     qmd --index index status
     ```
   - Also check whether a tracked embed process is running with `process list`/`process poll`; stale pending counts often mean no active process.
   - Use `Vectors` and `Pending` from CLI status, not document counts from `mcp_qmd_status`.
   - Calculate progress as `Vectors / (Vectors + Pending) * 100`.
   - Do not calculate embedding percent as `(Total documents - Needs embedding) / Total documents`; embedding is tracked by vector/chunk count, not file count.
   - QMD's total vector/chunk count can change between runs while chunking/index metadata is recalculated; report the current `Vectors`, `Pending`, and computed percent.

6. Diagnosing failed embeddings:
   - QMD CLI usually prints only aggregate failure counts such as `⚠ 408 chunks failed`, not the exact file/chunk names.
   - In QMD 2.1.0, `dist/store.js` increments embed errors when `embedBatch()` returns nulls, batch embedding throws and individual fallback also fails, or the LLM session becomes invalid.
   - List remaining pending documents directly from SQLite when needed:
     ```bash
     python3 - <<'PY'
     import sqlite3
     con=sqlite3.connect('/root/.cache/qmd/index.sqlite')
     con.row_factory=sqlite3.Row
     print('by collection')
     for r in con.execute('''
       SELECT d.collection, COUNT(DISTINCT d.hash) cnt, SUM(length(CAST(c.doc AS BLOB))) bytes
       FROM documents d JOIN content c ON d.hash=c.hash
       LEFT JOIN content_vectors v ON d.hash=v.hash AND v.seq=0
       WHERE d.active=1 AND v.hash IS NULL
       GROUP BY d.collection ORDER BY cnt DESC
     '''):
       print(dict(r))
     print('\ntop pending by size')
     for r in con.execute('''
       SELECT d.collection, d.path, d.title, length(CAST(c.doc AS BLOB)) bytes
       FROM documents d JOIN content c ON d.hash=c.hash
       LEFT JOIN content_vectors v ON d.hash=v.hash AND v.seq=0
       WHERE d.active=1 AND v.hash IS NULL
       ORDER BY bytes DESC LIMIT 30
     '''):
       print(f"{r['bytes']:7d} {r['collection']}/{r['path']} | {r['title'][:60]}")
     PY
     ```

7. Report back:
   - Which repos had new commits (and how many, e.g. from `git pull` output).
   - Updated doc count and `needsEmbedding` from `qmd status`.
   - For embedding work, include `Vectors`, `Pending`, computed progress percentage, current process status, and any aggregate failure count.

## Alternatives

The same flow is exposed as Modal entrypoints — use these when triggering from a developer machine, not from inside an active gateway turn:

- `modal run modal_app.py::sync_qmd` — pulls + `qmd update`.
- `modal run modal_app.py::sync_qmd --embed` — also runs `qmd embed`.

## Pitfalls

- Branch is `master`, not `main`. Substituting fails the pull.
- If `git pull --ff-only` fails (force-pushed upstream), surface the conflict to the user. Do not `reset --hard` without explicit confirmation.
- Embedding runs on CPU here (`QMD_LLAMA_GPU=false`); large diffs can take many minutes — confirm before running `embed -f`.
- A background `qmd embed` may stop before completion, including past cases with exit code 143/SIGTERM. If progress stalls, check `process list` and `qmd --index index status`; if no process is running and Pending remains non-zero, restart embedding rather than assuming it is still working.
- `qmd embed` can exit 0 while still reporting failed chunks and leaving Pending non-zero. A smaller batch retry (`--max-docs-per-batch 1 --max-batch-mb 1`) is a proven next step in this environment.
- Auth is baked into the existing remote URLs (HTTPS+token or the SSH key configured at first clone). Don't rewrite remotes.
