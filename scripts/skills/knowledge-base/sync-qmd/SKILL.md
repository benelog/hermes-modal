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
   - For an interactive user request, run normal embed as a tracked background process so the chat can continue and the user can ask for progress:
     ```
     qmd --index index embed
     ```
     with `terminal(background=true, notify_on_complete=true)`.
   - To report progress, compare the initial `Pending: N need embedding` from `qmd --index index status` before starting with the current pending count from another `qmd --index index status`. Progress percent is `(initial_pending - current_pending) / initial_pending * 100`.
   - Use Hermes `process poll`/`process list` for the tracked background job status; do not rely on OS process tools in this environment, because minimal containers may lack `pgrep`/`ps`.

5. Report back:
   - Which repos had new commits (and how many, e.g. from `git pull` output).
   - Updated doc count and `needsEmbedding` from `qmd status`.
   - If embedding was started, include the background session id and later progress as completed/pending counts plus percentage.

## Alternatives

The same flow is exposed as Modal entrypoints — use these when triggering from a developer machine, not from inside an active gateway turn:

- `modal run modal_app.py::sync_qmd` — pulls + `qmd update`.
- `modal run modal_app.py::sync_qmd --embed` — also runs `qmd embed`.

## Pitfalls

- Branch is `master`, not `main`. Substituting fails the pull.
- If `git pull --ff-only` fails (force-pushed upstream), surface the conflict to the user. Do not `reset --hard` without explicit confirmation.
- Embedding runs on CPU here (`QMD_LLAMA_GPU=false`); large diffs can take many minutes — confirm before running `embed -f`.
- Auth is baked into the existing remote URLs (HTTPS+token or the SSH key configured at first clone). Don't rewrite remotes.
