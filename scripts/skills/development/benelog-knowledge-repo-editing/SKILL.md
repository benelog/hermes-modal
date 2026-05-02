---
name: benelog-knowledge-repo-editing
description: Edit benelog knowledge-base repositories such as devnote/wiki/blog/diary/bookshelf content files, including locating notes, adding cited links with concise summaries, using external metadata when needed, verifying diffs, and respecting commit/deploy conventions.
---

# Benelog Knowledge Repo Editing

## When to use

Use this when the user asks to add, update, or reorganize content in a benelog markdown/asciidoc knowledge repository, especially:

- `/root/workspace/benelog/devnote/content/*.md`
- `/root/workspace/benelog/wiki/content/*`
- `/root/workspace/benelog/blog/src/content/*`
- `/root/workspace/benelog/diary/content/*`
- `/root/workspace/benelog/bookshelf/content/*`
- `/root/workspace/benelog/bookshelf-it/content/*`

This is for repository content editing. For only pulling QMD repos and refreshing the index, use `sync-qmd` instead.

## Procedure

1. Load this skill, then inspect repository guidance if present:
   - Check repo-local files such as `CLAUDE.md`, `README.md`, or site config when relevant.
   - In devnote, page files are flat under `content/*.md`; filenames are page titles and H1 is normally not used for the page title.

2. Locate the target note and section:
   - Prefer `search_files` for likely Korean/English headings and keywords.
   - If file name is obvious, read it directly with line numbers.
   - Do not assume a section name; verify the exact heading and nearby context.

3. If adding a web/video link, gather enough metadata to avoid hallucinating:
   - Use official page metadata, oEmbed, or scraped description/chapters when available.
   - For YouTube, `yt-dlp` and transcript APIs may be blocked in cloud environments; see `references/youtube-metadata-for-notes.md` for a fallback.
   - Keep summaries grounded in retrieved title/description/chapters or user-provided content.

4. Edit in the existing note style:
   - Preserve the existing bullet indentation and link style.
   - Put the item in the most specific existing section.
   - If the user requests a fixed number of summary lines, produce exactly that count.
   - For plugin/tool summaries, one nested bullet per plugin/tool is often clearer than a prose paragraph.

5. Verify before replying:
   - Show `git diff -- <file>` or otherwise inspect the modified region.
   - Check `git status --short` for unintended files.
   - Do not commit or push unless the user explicitly asks.
   - Mention the modified file and whether changes are only in the working tree.

6. If the user explicitly asks to commit and push:
   - Ensure git identity is configured for the repo; for benelog repos, `Sanghyuk Jung <benelog@gmail.com>` is the established identity if missing.
   - If there are uncommitted changes from this task, `git add <files>` and commit them first, then run `git pull --rebase origin master` and `git push origin master`.
   - If push is rejected because remote has new commits, rebase onto `origin/master` and push again.
   - Verify with `git status --short` and report the final short commit hash.

## Pitfalls

- Do not run full QMD sync/update unless the user asks for search/index refresh; a simple content edit only needs a file change and diff verification.
- Do not cite raw local markdown paths as if they are public pages. If you quote/cite a QMD/benelog file to the user, include the deployed URL when applicable.
- YouTube transcripts often fail from cloud IPs with bot checks. Use oEmbed and `r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=...` to retrieve title, description, and chapters if available.
- Keep Korean notes concise; avoid adding long explanations when the user asked for a short summary.

## References

- `references/youtube-metadata-for-notes.md` — fallback commands and observations for extracting YouTube title/description/chapters when direct transcript tools are blocked.
