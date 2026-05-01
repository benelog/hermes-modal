---
name: qmd-indexing-smoke-test
description: Test whether QMD indexing/search is working for a user question, especially when initial broad semantic/reranked queries time out or return weak results.
---

# QMD Indexing Smoke Test

Use this when the user asks whether QMD is working, whether a collection is indexed, or asks a question intended to test QMD retrieval.

## Maintenance / repair workflow

Use this when the user asks to re-index, re-embed, or fix QMD health warnings.

1. Capture the starting state with `qmd status` and preserve the meaning of the counters in your explanation:
   - `Missing`: documents lacking embeddings for the effective model.
   - `Mismatch`: documents with embeddings from a different model.
   - `Search Policy Health`: Korean lexical index health; `untracked index` usually needs `qmd update`.
2. Rebuild the Korean lexical index first:
   - Run `qmd update`.
   - Verify `Search Policy Health: clean` and `Indexed: total/total`.
3. Rebuild embeddings second:
   - If the model changed or status recommends it, run `qmd embed --force`.
   - Otherwise run `qmd embed` to fill only missing embeddings.
   - This QMD CLI version accepts only `qmd embed [-f|--force]`; do not add batch-size options even if help text advertises them, unless `qmd embed --help` proves they are accepted.
4. Explain apparent progress reversals correctly:
   - After `--force`, old mismatched embeddings can become missing current-model embeddings.
   - Example: `Missing: 0, Mismatch: 995` can legitimately become `Missing: 975, Mismatch: 0` after the first 20 documents are rebuilt.
5. Long CPU embedding runs may terminate or be interrupted. If continuing in the background:
   - Start with `qmd embed` rather than `--force` once mismatches are cleared, so it resumes missing documents.
   - Poll `qmd status` for actual progress; process handles can disappear even after partial progress.
   - If no `ps` exists, scan `/proc/*/cmdline` for `qmd embed`.
6. Verify completion with `qmd status`:
   - `Missing: 0`, `Mismatch: 0`, `Embedding Model Health: clean`, and `Search Policy Health: clean`.

See `references/qmd-reindex-reembed-watchdog.md` for a concrete session transcript pattern, including Hermes cron scheduler pitfalls.

## Steps

1. Check index health first:
   - Call `mcp_qmd_status()` or run `qmd status` when the MCP tool is unavailable or maintenance commands are needed.
   - Confirm total docs, collections, and vector index availability.
   - Note if `needsEmbedding`/`Missing` is non-zero; search can still work, but some semantic recall may be incomplete.
   - Distinguish `Missing` from `Mismatch`: `Mismatch` means embeddings exist but were produced by a different model than the effective model, so semantic search may be stale until rebuilt.

2. Start with the most likely collection(s), not all collections if the domain is obvious:
   - Reading/book questions: `bookshelf`, sometimes `bookshelf-it`.
   - Personal timeline questions: `diary`.
   - Technical notes: `devnote`, `wiki`, `blog`.

3. For QMD queries, avoid relying on a single broad reranked query:
   - Broad `lex + vec + hyde` across many collections may time out.
   - If it times out, retry narrower and faster.

4. Retry strategy after timeout or empty results:
   - Narrow `collections` to the likely collection.
   - Set `rerank: false` for speed.
   - Lower `candidateLimit` if using semantic search.
   - Try both Korean and English terminology.
   - Run independent lex and vec searches in parallel if useful.

5. Example for Korean book/comic retrieval:
   - First try lex terms such as `만화 OR 만화책 OR 그래픽노블 OR manga OR 코믹스`.
   - If lex returns no results, try a semantic query like `books that are comics manga graphic novels read by the user`.
   - Treat semantically returned items cautiously; inspect snippets/tags to distinguish actual comics from books about comics.

6. When answering:
   - Report the retrieved items directly.
   - Mention uncertainty for borderline matches.
   - State that QMD indexing/search appears to work only if results are grounded in returned docs/snippets.

## Pitfalls

- QMD lex syntax may not behave like a full Boolean query in all cases; `OR` can still return no results if terms do not match indexed text as expected.
- A top semantic result can be related but not an answer, e.g. a book about webtoon creation rather than a comic book.
- Do not conclude indexing is broken from one empty lex query; retry with vector search and alternate terms.
