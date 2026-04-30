---
name: qmd-reading-preference-analysis
description: Infer a user's book/reading preferences from QMD bookshelf collections, especially when broad QMD queries time out or direct filesystem parsing is unreliable.
---

# QMD Reading Preference Analysis

Use this when the user asks for their book taste, reading preferences, favorite topics, or patterns from QMD `bookshelf` / `bookshelf-it` collections.

## Approach

1. Use QMD first, not raw filesystem parsing.
   - Primary collections: `bookshelf`, `bookshelf-it`.
   - `bookshelf` usually contains general reading notes.
   - `bookshelf-it` usually contains software-engineering/technical book notes.

2. Avoid one huge broad query.
   - Broad `lex + vec + hyde` across collections can time out.
   - If a broad query times out, switch to multiple narrow queries with `rerank: false`.
   - Prefer parallel independent searches for themes.

3. Probe likely themes separately with semantic queries.
   - General management/org/work:
     - `books about management organizations leadership business that user read`
   - Science/evolution/brain/anthropology:
     - `books about science evolution brain anthropology physics that user read`
   - Running/health/exercise:
     - `books about running health marathon exercise that user read`
   - Software engineering:
     - `software engineering programming architecture agile testing books that user read` against `bookshelf-it`
   - Comics/graphic nonfiction if relevant:
     - `books that are comics manga graphic novels read by the user`

4. Treat lex/vector search cautiously.
   - Korean lex queries with `OR` may return no results even when semantically relevant docs exist.
   - Vector queries can fail if the QMD database/index is temporarily unhealthy (for example, `database disk image is malformed`).
   - If lex returns empty, retry with vector search in English/Korean when available.
   - Use snippets and tags to verify each theme.

5. When QMD is empty, weak, or malformed, use a targeted raw-file fallback for exact preference phrases.
   - Prefer `execute_code` with Python `pathlib` to scan `/root/workspace/benelog/bookshelf/content/post` and `/root/workspace/benelog/bookshelf-it/content/post` directly; this avoids the Hermes `read_file` 50-tool-call limit because Python reads files from disk itself.
   - Search for Korean/English preference markers such as `가장 재미`, `재미있게 읽`, `재밌게`, `재밌`, `잡자마자`, `끝까지 한번에`, `끝까지 한 번에`, `재미있었습니다`, `재미있었다`, `올해 읽은 책 중 최고의 책`, `좋아하는`, `감동`.
   - Extract title from frontmatter and print file path, title, line number, and matching line; then read only the top candidate files with `read_file` for verification and citation.
   - This fallback is especially useful for questions like “내가 가장 재미있게 읽은 책은?” where exact wording in notes matters more than semantic clustering.

6. Synthesize preferences by repeated clusters, not isolated hits.
   - Strong clusters observed in this user's QMD included:
     - software engineering: architecture, TDD, OO design, agile, readable code, Java/Spring
     - organization/leadership: Peopleware, Slack, team lead, leadership questions, work communication
     - productivity/learning: habits, focus, talent, learning methods, brain-based reading/work
     - science: evolution, brain, humanity, physics, environmental science
     - running/health: marathon, Born to Run, running + flow
     - society/future: inequality, population, food, technology/future
     - graphic/comics as knowledge delivery: manga/graphic versions of science, business, history

6. Answer format:
   - Start with a concise one-line characterization.
   - Group by theme with representative titles.
   - Distinguish strong vs weak/non-central tastes.
   - Note that conclusions are based on QMD search results, not a complete statistical analysis unless you actually computed one.

## Pitfalls

- Directly parsing many files via `execute_code` + hermes `read_file` can hit the 50-tool-call limit.
- Raw `terminal` filesystem traversal may hang or time out in this environment; use QMD retrieval instead unless exact counts are essential.
- Do not overstate genres from single hits; require multiple returned examples before calling something a preference.
- Some hits are related but not direct evidence of taste, e.g. a book about webtoon creation is not necessarily a comic book read for entertainment.
