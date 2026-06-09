---
name: benelog-diary-github-activity
description: Create a benelog diary post summarizing a day's GitHub and Hermes/QMD activity, distinguishing user actions from agent-assisted actions, then commit and push to the diary repo.
---

# Benelog diary from GitHub activity

## When to use

Use when the user asks to summarize today's GitHub activity as a diary draft, save such a draft into the `benelog/diary` repo, or revise a diary entry based on GitHub/Hermes/QMD work done during the day.

## Key preferences

- Use Korea time (`Asia/Seoul`) for “today”.
- The diary is a Hugo site under `/root/workspace/benelog/diary`.
- Post files live under `content/post/<year>/<slug>.md`.
- Deployed URL format is `https://diary.benelog.net/<year>/<slug>/`.
- If citing or linking QMD/benelog content, provide the deployed URL, not only the source path.
- If the conversation shows that Hermes Agent performed a GitHub operation on the user's behalf, do **not** write the diary as if the user personally executed it. Phrase it as: the user requested it and Hermes Agent performed it.

## Procedure

1. **Gather today's GitHub activity**
   - Get current date/time with `date` or Python, and compute the KST day boundary.
   - Query GitHub public events and/or commits for the relevant account and repos, e.g.:
     ```python
     https://api.github.com/users/benelog/events/public?per_page=100
     https://api.github.com/repos/<owner>/<repo>/commits?since=<UTC>&until=<UTC>&per_page=100
     ```
   - Summarize by repo, event type, and commit message.
   - Public events may show `PushEvent` with `0 commits`; use the commits API for actual commit messages.

2. **Recover context beyond raw GitHub commits**
   - Use `session_search` when the diary should include Hermes/Modal/QMD work or when the user says an action was done by the agent.
   - Search likely terms such as `hermes-modal OR SOUL.md OR QMD OR skills-autocommit OR GITHUB_TOKEN OR presentations`.
   - This is important because some meaningful work (skill changes, QMD checks, PR creation/merge by the agent) may not be visible as commits in the user's public GitHub events.

3. **Draft the diary entry**
   - Use first-person diary style in Korean unless the user asks otherwise.
   - Avoid a dry bullet list unless requested; convert activity into a narrative.
   - Include:
     - What was done in each repo.
     - Why it mattered.
     - What was done by Hermes Agent versus by the user.
     - Any Hermes Modal/QMD environment insights if relevant.
   - Keep factual claims grounded in GitHub API results, file contents, or session_search summaries.

4. **Write the file**
   - Choose a readable slug, e.g. `github-hermes-work.md`.
   - Create frontmatter similar to:
     ```yaml
     ---
     title: "GitHub와 Hermes 작업 기록"
     date: 2026-04-26T21:59:00+09:00
     categories: ["2026년"]
     description: "발표 자료 저장소, 위키, Hermes Modal 환경을 정리한 하루"
     tags: ["github", "hermes", "발표"]
     ---
     ```
   - Write to `/root/workspace/benelog/diary/content/post/<year>/<slug>.md`.

5. **Verify and commit**
   - Read the file back with `read_file`.
   - Check `git status --short` in `/root/workspace/benelog/diary`.
   - If `hugo` is installed, run a Hugo build. If not installed, report that build verification could not be run.
   - Commit with the latest repo author identity if global git identity is missing:
     ```bash
     git log -1 --format='%an <%ae>'
     GIT_AUTHOR_NAME='Sanghyuk Jung' GIT_AUTHOR_EMAIL='benelog@gmail.com' \
     GIT_COMMITTER_NAME='Sanghyuk Jung' GIT_COMMITTER_EMAIL='benelog@gmail.com' \
     git commit -m '<message>'
     ```
   - Push to the current tracked branch (usually `master`):
     ```bash
     git push origin master
     ```

## Pitfalls

- Do not assume GitHub public events alone contain all work. Agent-created PRs, Modal/Hermes discoveries, and QMD skill updates may need `session_search`.
- Do not misattribute agent actions to the user. The user explicitly corrected that a `presentations` branch/PR was created by Hermes Agent.
- `benelog/hermes-modal` may not be visible through public GitHub APIs; use session history and local Modal/Hermes files for context.
- Browser/Chrome may be unavailable in Modal; avoid relying on browser tools for GitHub review. GitHub REST API and `git` are more reliable.
- `hugo` may not be installed in the runtime. If unavailable, still verify file contents and git state, and mention the limitation.
