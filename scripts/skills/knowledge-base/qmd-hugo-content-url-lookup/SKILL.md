---
name: qmd-hugo-content-url-lookup
description: Find a QMD-indexed Hugo content document and derive its deployed URL from repository config/permalink settings, especially when mcp_qmd_get returns empty.
---

# QMD Hugo Content URL Lookup

Use this when the user asks to find a diary/blog/wiki/bookshelf post in QMD and/or asks for the deployed URL/path of a QMD-indexed markdown document.

## Workflow

1. Search QMD for the document.
   - Restrict to the likely collection when known, e.g. `diary`, `blog`, `bookshelf`, `bookshelf-it`.
   - Use a precise lex query for known titles, e.g. `"먼저 온 미래" 감상문`.
   - Capture the QMD result file path, e.g. `diary/post/2025/future-came-first.md`.

2. Retrieve content.
   - Try `mcp_qmd_get` by file path or docid.
   - If `mcp_qmd_get` returns an empty result, fall back to `read_file` using the repository content root shown by `mcp_qmd_status`.
   - Example mapping:
     - QMD file: `diary/post/2025/future-came-first.md`
     - repo content path: `/root/workspace/benelog/diary/content/post/2025/future-came-first.md`
   - Read additional offsets if the file is truncated.

3. Find the site base URL and permalink rules.
   - Search/list repository root files if needed.
   - For Hugo sites, inspect `config.toml` / config files.
   - Example `diary` config:
     - `baseURL = "https://diary.benelog.net/"`
     - `[permalinks] post = ":year/:filename/"`

4. Derive the deployed URL.
   - Determine section/type and filename from the content path.
   - For `content/post/YYYY/slug.md` with `post = ":year/:filename/"`, URL is:
     - `{baseURL}/{YYYY}/{slug}/`
   - Example:
     - File: `content/post/2025/future-came-first.md`
     - URL: `https://diary.benelog.net/2025/future-came-first/`

5. Respond with evidence.
   - Provide the deployed URL first.
   - Include the source file path and the config/permalink facts used.
   - Mention if the URL is derived rather than fetched live, unless a browser/web check was performed.

## Pitfalls

- QMD search can find a document even when `mcp_qmd_get` returns an empty string; use repository filesystem fallback.
- Do not assume all Hugo sites use `/post/YYYY/slug/`; inspect `[permalinks]`.
- Do not assume `date` determines URL when the path already contains the year and permalink uses `:year`; verify frontmatter date if there is a mismatch.
- Avoid broad QMD searches when the title is known; precise lex search is faster and less likely to time out.
