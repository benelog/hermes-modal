---
name: knowledge-repo-editing
description: Edit the user's benelog knowledge repositories by finding the right page/section, adding or updating links with grounded summaries, verifying diffs, and committing/pushing by default.
---

# Knowledge Repo Editing

## When to use

Use this skill when the user asks to add, update, summarize, replace, or reorganize content in their benelog knowledge repositories, especially when a URL is involved.

Typical requests:

- "이 링크 devnote에 추가해줘"
- "적절한 페이지 찾아서 링크와 요약 추가해줘"
- "요약 N줄로 넣어줘"
- "이 동영상/글을 내 지식 저장소에 정리해줘"
- "해당 동영상이 참고한 원문 링크로 바꾸고 commit/push까지 해줘"
- Edits under `devnote`, `wiki`, `blog`, `diary`, `bookshelf`, or `bookshelf-it`

This skill is for repository content editing. For only pulling QMD repos and refreshing the index, use `sync-qmd` instead.

## Default behavior

- **Commit and push are the default** after a successful knowledge-repo edit.
- Keep the commit scoped to the current user request only.
- Do **not** commit unrelated pre-existing changes. If unrelated changes are present, pause and ask how to handle them.
- Do not run QMD indexing/sync unless the user explicitly asks for index refresh.

## Repositories

The user's knowledge repositories usually live under `/root/workspace/benelog/`:

- `devnote/content/*.md` — development notes, flat Markdown files; filenames are page titles and H1 is normally not used for page title
- `wiki/content/*` — wiki notes
- `blog/src/content/*` — blog content
- `diary/content/*` — diary entries
- `bookshelf/content/*` — general book notes
- `bookshelf-it/content/*` — IT book notes

Default to `devnote` only when the user names devnote or the topic is clearly development-related. Otherwise search across all relevant repos.

## Procedure

1. **Understand the request**
   - Extract URL(s), requested repository/page/section if any, requested summary length, and any replacement instruction.
   - If the user requests exactly N summary lines, add exactly N nested bullets.
   - If the user does not specify summary length, use 3–5 concise nested bullets depending on source density.

2. **Inspect repository guidance**
   - Check repo-local files such as `CLAUDE.md`, `README.md`, or site config when relevant.
   - Respect repo conventions, especially list indentation, section structure, and language style.

3. **Fetch reliable source metadata/content**
   - If the user provides an aggregator/share URL such as `https://share.google/...`, resolve redirects first with `requests.get(..., allow_redirects=True)` or `curl -Ls -o /dev/null -w '%{url_effective}'` and use the canonical destination URL in the note unless the user explicitly wants the share URL preserved.
   - For normal web pages, retrieve title, headings, description, and key points with `curl`/`python` or Jina Reader:
     - Use `https://r.jina.ai/http://https://<host>/<path>` for HTTPS pages (or `https://r.jina.ai/http://http://<host>/<path>` for HTTP pages).
     - If a Jina Reader URL returns 403 or empty content, retry with the original URL scheme explicitly included after `/http://`; avoid accidentally nesting `r.jina.ai` twice.
   - For YouTube, direct transcript tools may be blocked from cloud IPs. Prefer:
     - `https://www.youtube.com/oembed?url=<youtube-url>&format=json` for title/channel.
     - `https://r.jina.ai/http://r.jina.ai/http://<youtube-url>` for title, description, chapters, timestamps, and pinned links.
     - See `references/youtube-metadata-for-notes.md` for fallback commands.
   - Ground summaries only in retrieved metadata, page content, or user-provided context. Do not hallucinate transcripts or details.

4. **Find the best target page and section**
   - Search candidate repos with `search_files` using source title keywords, topic keywords, and related Korean/English terms.
   - Prefer an existing specific page/section over creating a new page.
   - For development tools/libraries/services, actively search for broader existing topic pages before creating a page named after the tool (e.g. `ngrok` belongs better in `cloud-deployment`/`network` if those pages exist; `Scrapling` may belong in `web-scraping` if that topic page exists).
   - If the user names a repo but not a page, search only that repo first; broaden only if no reasonable page exists.
   - Create a new page only after verifying there is no reasonable existing page/section; if the user later says “기존 페이지에 추가해” or equivalent, migrate the note into the best existing page and delete the unnecessary new page in a follow-up commit.
   - Read the candidate file with line numbers and verify exact heading plus nearby context before editing.
   - Do not assume a section name; verify the actual heading and style.

5. **Edit in existing note style**
   - Preserve existing bullet indentation and link style.
   - Insert the item in the most specific relevant section, not just at the end.
   - Use canonical/original source URLs when the user asks to replace a video with its referenced article.
   - If the user asks to switch existing article links to Naver News sources, search Naver News by exact/near-exact article title, verify the Naver result title matches the existing article, and replace only confidently matched `n.news.naver.com` URLs. Leave non-matches unchanged and report which ones were changed or already Naver.
   - Keep Korean notes concise; avoid long explanations when a short summary was requested.
   - For plugin/tool summaries, one nested bullet per plugin/tool is often clearer than a prose paragraph.

6. **Verify the edit**
   - Run `git diff --check` in the modified repo.
   - Inspect `git diff -- <path>` and ensure only intended lines changed.
   - Run `git status --short` and check for unrelated files.
   - If the repository was clean at the start and the edit may conflict with recent remote knowledge-base updates, prefer `git pull --rebase origin master` before editing or immediately before final verification to reduce post-commit conflicts.

7. **Commit and push by default**
   - Configure repo identity if missing:
     - `git config user.name "Sanghyuk Jung"`
     - `git config user.email "benelog@gmail.com"`
   - Stage only files modified for this request.
   - Commit with a concise message, e.g. `Add <topic> link summary`, `Update <topic> reference`, or `Add <page> notes`.
   - Run `git pull --rebase origin master` after committing.
     - If a simple conflict occurs only in the file edited for this task, resolve it when the intended merge is obvious: preserve upstream restructuring/renamed headings and insert only the scoped new note/link in the most appropriate surviving section. Then run `git diff --check`, `git add <path>`, and `GIT_EDITOR=true git rebase --continue`.
     - If the conflict affects unrelated content, has ambiguous ordering/meaning, or spans files outside the request scope, stop and report it instead of guessing.
   - Run `git push origin master`.
   - Verify clean status and capture `git rev-parse --short HEAD`.

## Final response

Report concisely in Korean:

- Modified repo/file
- Added/updated/replaced link title and URL
- Summary line count if requested
- Commit hash and push result
- Any important caveat, such as limited source metadata

## Pitfalls

- Do not commit unrelated pre-existing changes.
- Do not cite raw local markdown paths as if they are public pages. If quoting/citing QMD/benelog files to the user, include the deployed URL when applicable.
- For devnote deployed URLs, `content/<slug>.md` maps to `https://devnote.benelog.net/<slug>`; verify with a quick `curl -I`/HTTP status check when first using a slug in a session.
- For wiki repo deployed URLs, `site.yaml` may expose the host in `subtitle` (e.g. `wiki.benelog.net`); combine it with the content filename slug such as `content/ai-trend.md` → `https://wiki.benelog.net/ai-trend`.
- For wiki `ai-trend.md` link placement conventions, including the user's preferred messenger export format and date sorting rules, see `references/wiki-ai-trend-placement.md`.
- For wiki `ai-trend.md` date-order verification, use `scripts/check-ai-trend-sort.py <path-to-content/ai-trend.md>`.
- YouTube transcripts often fail from cloud IPs with bot checks; use oEmbed and Jina Reader fallback.
- If `git pull --rebase` refuses due to unstaged changes, commit the scoped task first, then rebase and push.
- If a precise `patch` edit unexpectedly reports multiple matches, do not force a broad replacement. Re-read nearby lines and use a small deterministic script to insert/replace relative to a verified unique heading or marker, then inspect `git diff` carefully.
- Some news sites (e.g. mobile/AMP Seoul Economic Daily URLs) may return 403 to direct `requests`/`curl` while Jina Reader succeeds; use Jina for source extraction and keep the original user-provided/canonical URL in the note.
- If the user explicitly says not to commit/push, leave changes in the working tree and report that state.
