---
name: qmd-result-citation
description: When citing a QMD search hit to the user, convert the source file path into the deployed URL on benelog.net instead of pasting the raw markdown/asciidoc path or contents.
---

# QMD Result Citation

## When to use

After running QMD search, reading a QMD/benelog markdown or AsciiDoc file, or quoting any content from those files to the user. The user expects a clickable deployed URL whenever a QMD/benelog source file is cited or quoted, even when they ask for the full raw content of a short document.

The user almost always wants a clickable URL to the published page, not only the file path under `/root/workspace/benelog/...`.

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

1. Receive a QMD hit. Extract the **collection name** (== repo name) and the **file path relative to the collection root** (the `path:` value in `index.yml`, e.g. `bookshelf/content` → relative path is `post/<name>.md`).
2. Apply the rule for that collection's SSG (table above).
3. Render in the message as a clickable markdown link with the page title (from frontmatter `title:` for Hugo, the AsciiDoc `=` heading for blog, or the file's H1 for obsidian-site). Add a one- or two-line snippet from the QMD result so the user can decide whether to click.
4. If the user asks for the actual content, still put the deployed URL first. Prefer a short excerpt for long documents; for very short documents where the user explicitly asks for the whole content, it is acceptable to quote the full markdown/asciidoc body after the URL.

## Pitfalls

- `tags`, `archive`, `index`, `_index.md`, `tags.adoc` etc. are meta pages with their own routing. If a QMD hit points at one of these, link to the site root instead and note that it's an index page.
- Korean filenames in URLs are valid; do not URL-encode them when displaying — both the browser address bar and Markdown link renderers handle them. (Some Telegram clients show the percent-encoded form; that's display-only and the link still works.)
- diary URLs need the year segment. A bare `<basename>` will silently 404. Always include the directory between `content/post/` and the file.
- If a frontmatter `url:`, `slug:`, `permalink:`, or `:jbake-uri:` override appears (rare — none in the current corpus as of this skill being written), trust the override over the rule.
