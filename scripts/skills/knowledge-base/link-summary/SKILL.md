---
name: link-summary
description: Given a link, find the best matching page in the user's benelog knowledge repositories, add the link with a concise grounded summary, then commit and push the change.
---

# Link Summary

## When to use

Use this skill when the user gives a URL and asks to add it to their knowledge repository, especially when they do not specify an exact file/page. Typical requests:

- "이 링크 devnote에 추가해줘"
- "적절한 페이지 찾아서 링크와 요약 추가하고 commit/push까지 해줘"
- "요약 N줄로 넣어줘"
- "이 동영상/글을 내 지식 저장소에 정리해줘"

For benelog repository editing conventions, also load `benelog-knowledge-repo-editing` if available.

## Repositories

The user's knowledge repositories usually live under `/root/workspace/benelog/`:

- `devnote/content/*.md` — development notes, flat Markdown files
- `wiki/content/*` — wiki notes
- `blog/src/content/*` — blog content
- `diary/content/*` — diary entries
- `bookshelf/content/*` — general book notes
- `bookshelf-it/content/*` — IT book notes

Default to `devnote` only when the user names devnote or the topic is clearly development-related. Otherwise search across all relevant repos.

## Procedure

1. **Understand the request**
   - Extract the URL(s), requested repository if any, requested summary length, and whether commit/push is required.
   - If the user does not specify summary length, use 3–5 concise nested bullets depending on source density.
   - This skill defaults to commit and push because that is part of its purpose; still keep the commit scoped to this task only.

2. **Fetch reliable source metadata**
   - For normal web pages, use `curl`/`python` or `https://r.jina.ai/http://r.jina.ai/http://<URL>` to retrieve title, headings, description, and key points.
   - For YouTube, direct transcript tools may be blocked from cloud IPs. Prefer:
     - `https://www.youtube.com/oembed?url=<youtube-url>&format=json` for the title/channel.
     - `https://r.jina.ai/http://r.jina.ai/http://<youtube-url>` for title, description, chapters, timestamps, and pinned links.
   - Ground summaries only in retrieved metadata, page content, or user-provided context. If details are unavailable, say so briefly or summarize only the title/description.

3. **Find the best target page**
   - Search candidate repos with `search_files` using source title keywords, topic keywords, and related Korean/English terms.
   - Prefer an existing specific page/section over creating a new page.
   - If the user names a repo but not a page, search only that repo first, then broaden only if no reasonable page exists.
   - Read the candidate file and verify the exact section and nearby style before editing.

4. **Edit in repository style**
   - Preserve existing bullet/list indentation and language style.
   - Insert the link in the most relevant section, not just at the end.
   - Use the canonical/original source URL when the user asks to replace a video with its referenced article.
   - If the user requests exactly N summary lines, add exactly N nested bullets.
   - Keep each summary bullet short and actionable. For tool/plugin lists, one bullet per tool/plugin is preferred.

5. **Verify the edit**
   - Run `git diff --check` in the modified repo.
   - Inspect `git diff -- <path>` and ensure only intended lines changed.
   - Run `git status --short` and check for unrelated files.

6. **Commit and push**
   - Configure repo identity if missing:
     - `git config user.name "Sanghyuk Jung"`
     - `git config user.email "benelog@gmail.com"`
   - Stage only files modified for this request.
   - Commit with a concise message, e.g. `Add <topic> link summary`.
   - Run `git pull --rebase origin master`, resolving only simple non-conflicting rebases automatically. If conflicts occur, stop and report them.
   - Run `git push origin master`.
   - Verify clean status and capture `git rev-parse --short HEAD` for the final response.

## Final response

Report concisely in Korean:

- Modified repo/file
- Added/replaced link title and URL
- Summary line count if requested
- Commit hash and push result
- Any important caveat, e.g. source metadata was limited

## Pitfalls

- Do not commit unrelated pre-existing changes. If unrelated changes are present, pause and ask how to handle them.
- Do not hallucinate a transcript or article details. Use title/description/chapters/page text only.
- Do not run QMD indexing/sync unless the user explicitly asks for index refresh.
- For benelog/QMD citations in user-facing replies, include deployed URLs when quoting or citing stored content, if applicable.
- If `git pull --rebase` refuses due to unstaged changes, commit the scoped task first, then rebase and push.
