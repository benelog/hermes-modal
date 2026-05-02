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
   - For normal web pages, retrieve title, headings, description, and key points with `curl`/`python` or Jina Reader:
     - `https://r.jina.ai/http://r.jina.ai/http://<URL>`
   - For YouTube, direct transcript tools may be blocked from cloud IPs. Prefer:
     - `https://www.youtube.com/oembed?url=<youtube-url>&format=json` for title/channel.
     - `https://r.jina.ai/http://r.jina.ai/http://<youtube-url>` for title, description, chapters, timestamps, and pinned links.
     - See `references/youtube-metadata-for-notes.md` for fallback commands.
   - Ground summaries only in retrieved metadata, page content, or user-provided context. Do not hallucinate transcripts or details.

4. **Find the best target page and section**
   - Search candidate repos with `search_files` using source title keywords, topic keywords, and related Korean/English terms.
   - Prefer an existing specific page/section over creating a new page.
   - If the user names a repo but not a page, search only that repo first; broaden only if no reasonable page exists.
   - Read the candidate file with line numbers and verify exact heading plus nearby context before editing.
   - Do not assume a section name; verify the actual heading and style.

5. **Edit in existing note style**
   - Preserve existing bullet indentation and link style.
   - Insert the item in the most specific relevant section, not just at the end.
   - Use canonical/original source URLs when the user asks to replace a video with its referenced article.
   - Keep Korean notes concise; avoid long explanations when a short summary was requested.
   - For plugin/tool summaries, one nested bullet per plugin/tool is often clearer than a prose paragraph.

6. **Verify the edit**
   - Run `git diff --check` in the modified repo.
   - Inspect `git diff -- <path>` and ensure only intended lines changed.
   - Run `git status --short` and check for unrelated files.

7. **Commit and push by default**
   - Configure repo identity if missing:
     - `git config user.name "Sanghyuk Jung"`
     - `git config user.email "benelog@gmail.com"`
   - Stage only files modified for this request.
   - Commit with a concise message, e.g. `Add <topic> link summary`, `Update <topic> reference`, or `Add <page> notes`.
   - Run `git pull --rebase origin master` after committing. If conflicts occur, stop and report them.
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
- YouTube transcripts often fail from cloud IPs with bot checks; use oEmbed and Jina Reader fallback.
- If `git pull --rebase` refuses due to unstaged changes, commit the scoped task first, then rebase and push.
- If the user explicitly says not to commit/push, leave changes in the working tree and report that state.
