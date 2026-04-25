---
name: qmd-result-citation
description: When citing a QMD search hit to the user, convert the source file path into the deployed URL on benelog.net instead of pasting the raw markdown/asciidoc path or contents.
---

# QMD Result Citation

## When to use

Use this **immediately after every QMD search** when any result will be mentioned to the user. Do not first report raw QMD paths and then derive URLs only if the user asks; enrich the result list with clickable deployed URLs from the beginning.

The user almost always wants a clickable URL to the published page, not the file path under `/root/workspace/benelog/...` and not a wall of raw markdown/asciidoc.

Typical trigger phrases:
- "찾아줘", "검색해줘", "어디 있어?", "URL은?", "배포된 경로는?"
- Any answer that cites QMD hits like `diary/post/...`, `bookshelf/post/...`, `devnote/...`, etc.

## Repo → subdomain

Each QMD collection name maps to a subdomain on `benelog.net`:

| collection | subdomain |
|---|---|
| blog | `blog.benelog.net` |
| wiki | `wiki.benelog.net` |
| devnote | `devnote.benelog.net` |
| bookshelf | `bookshelf.benelog.net` |
| bookshelf-it | `bookshelf-it.benelog.net` |
| diary | `diary.benelog.net` |

## File path → URL

The path inside each repo follows site-specific rules. Use the *full path inside the collection*, not just the basename — the same basename can exist in different directories (e.g. diary has both `content/motivation.md` and `content/post/2017/motivation.md`).

### Hugo sites (bookshelf, bookshelf-it)
```
content/post/<name>.md  →  https://<repo>.benelog.net/<name>/
```
Trailing slash, no extension. Examples:
- `bookshelf/content/post/바깥은-여름.md` → `https://bookshelf.benelog.net/바깥은-여름/`
- `bookshelf-it/content/post/코딩을-지탱하는-기술.md` → `https://bookshelf-it.benelog.net/코딩을-지탱하는-기술/`

### Hugo: diary
```
content/<name>.md                    →  https://diary.benelog.net/<name>/
content/post/<year>/<name>.md        →  https://diary.benelog.net/<year>/<name>/
```
The year segment is the directory name, not parsed from frontmatter. Examples:
- `diary/content/about.md` → `https://diary.benelog.net/about/`
- `diary/content/post/2024/1000-blues.md` → `https://diary.benelog.net/2024/1000-blues/`
- `diary/content/post/2017/motivation.md` → `https://diary.benelog.net/2017/motivation/`

### obsidian-site (devnote, wiki)
```
content/<name>.md  →  https://<repo>.benelog.net/<name>.html
```
Flat content (no subdirectories). `.html` extension is required — internal links in the rendered site all carry it. Examples:
- `devnote/content/java-build.md` → `https://devnote.benelog.net/java-build.html`
- `wiki/content/만화-명대사.md` → `https://wiki.benelog.net/만화-명대사.html`

### Jbake (blog)
```
src/content/<name>.adoc  →  https://blog.benelog.net/<name>.html
```
Examples:
- `blog/src/content/2802943.adoc` → `https://blog.benelog.net/2802943.html`
- `blog/src/content/rethink-about-git-flow.adoc` → `https://blog.benelog.net/rethink-about-git-flow.html`

## Procedure

1. **Before writing the user-facing answer**, scan every QMD result you plan to mention and convert its `file` value into a deployed URL.
   - QMD often returns paths in the form `<collection>/<relative-path>`, e.g. `diary/post/2025/future-came-first.md` or `bookshelf/post/the-goal-만화판.md`.
   - Sometimes you may instead have an absolute repo path like `/root/workspace/benelog/diary/content/post/2025/future-came-first.md`.
   - Normalize either form into `(collection, relative path within the repo content root)`.
2. Extract the **collection name** (== repo name) and apply the rule for that collection's SSG (table above).
3. Render the first mention of each hit as a clickable markdown link, not as a raw path. Use the QMD title or frontmatter `title:` if available.
   - Good: `- [먼저 온 미래](https://diary.benelog.net/2025/future-came-first/) — 독서 감상문`
   - Bad: `- diary/post/2025/future-came-first.md`
4. Add a one- or two-line snippet or reason from the QMD result so the user can decide whether to click.
5. Only include the raw file path when it is specifically useful for local editing/debugging, and then put it after the URL as secondary information.
6. Do **not** dump the raw markdown/asciidoc body. If the user asks for the actual content, fetch/read it and quote a short excerpt — but link first by default.

## Quick conversion from QMD `file` values

QMD search result `file` values usually omit the `content/` segment. Convert directly like this:

| QMD `file` example | Published URL |
|---|---|
| `diary/post/2025/future-came-first.md` | `https://diary.benelog.net/2025/future-came-first/` |
| `diary/motivation.md` | `https://diary.benelog.net/motivation/` |
| `bookshelf/post/the-goal-만화판.md` | `https://bookshelf.benelog.net/the-goal-만화판/` |
| `bookshelf-it/post/growing-object-oriented-software-guided-by-test.md` | `https://bookshelf-it.benelog.net/growing-object-oriented-software-guided-by-test/` |
| `devnote/api-design.md` | `https://devnote.benelog.net/api-design.html` |
| `wiki/레전드-언론보도.md` | `https://wiki.benelog.net/레전드-언론보도.html` |
| `blog/rethink-about-git-flow.adoc` | `https://blog.benelog.net/rethink-about-git-flow.html` |

## Pitfalls

- `tags`, `archive`, `index`, `_index.md`, `tags.adoc` etc. are meta pages with their own routing. If a QMD hit points at one of these, link to the site root instead and note that it's an index page.
- Korean filenames in URLs are valid; do not URL-encode them when displaying — both the browser address bar and Markdown link renderers handle them. (Some Telegram clients show the percent-encoded form; that's display-only and the link still works.)
- diary URLs need the year segment. A bare `<basename>` will silently 404. Always include the directory between `content/post/` and the file.
- If a frontmatter `url:`, `slug:`, `permalink:`, or `:jbake-uri:` override appears (rare — none in the current corpus as of this skill being written), trust the override over the rule.
