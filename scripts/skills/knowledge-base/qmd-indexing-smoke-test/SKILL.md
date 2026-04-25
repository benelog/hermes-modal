---
name: qmd-indexing-smoke-test
description: Test whether QMD indexing/search is working for a user question, especially when initial broad semantic/reranked queries time out or return weak results.
---

# QMD Indexing Smoke Test

Use this when the user asks whether QMD is working, whether a collection is indexed, or asks a question intended to test QMD retrieval.

## Steps

1. Check index health first:
   - Call `mcp_qmd_status()`.
   - Confirm total docs, collections, and vector index availability.
   - Note if `needsEmbedding` is non-zero; search can still work, but some semantic recall may be incomplete.

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
