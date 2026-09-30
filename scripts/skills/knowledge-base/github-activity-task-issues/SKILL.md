---
name: github-activity-task-issues
description: Turn benelog GitHub activity for a date range into one closed tracking issue per repository in benelog/personal-task milestones.
---

# GitHub activity → personal-task tracking issues

## When to use

Use this when the user asks to register GitHub work into `benelog/personal-task` issues, especially phrasing like:

- "내가 GitHub에서 한 일을 repo 당 1개의 이슈로 등록해줘"
- "아래 마일스톤에 넣고 바로 닫아줘"
- "기간별 GitHub 활동을 personal-task에 기록해줘"

The expected output is not just a summary: create real GitHub issues in `benelog/personal-task`, attach the requested milestone, then close them.

## Key principles

- Use Korea time (`Asia/Seoul`) for the user's date ranges unless they specify otherwise.
- Interpret an inclusive date range such as `9월 21일 ~ 9월 28일` as `[2026-09-21T00:00:00+09:00, 2026-09-29T00:00:00+09:00)`.
- Create **one issue per repository per milestone/date range**.
- The issue should summarize the work in that repo for the range and include supporting commit / PR / issue links when available.
- After creating the issue, close it immediately.
- Verify by reading the created issue back or checking its returned state, milestone, and URL.

## Authentication reality check

Git commit/push credentials are not enough for GitHub issue operations.

- `git` CLI can push commits, but cannot create issues, assign milestones, or close issues.
- Issue creation and closing require GitHub API access via `gh` CLI or REST API.
- Before trying to write issues, check for usable API auth:
  - `GH_TOKEN`, `GITHUB_TOKEN`, or a configured `gh auth status`.
  - If only git push worked in previous tasks, do **not** assume issue API auth exists.
- If no issue-write auth exists, gather the activity summary that can be gathered from public APIs, then ask the user for a token or to configure auth. Do not claim the issues were created.

## Activity gathering workflow

1. **Compute KST boundaries**
   - Convert each user range to UTC `since` and `until` values for GitHub APIs.

2. **Use public events as a discovery source, not the only source**
   - `https://api.github.com/users/benelog/events/public?per_page=100&page=N`
   - Public events are capped/recent and `PushEvent` entries may omit commit messages or have empty commit arrays.
   - Events are still useful for issue comments, issue open/close, release, star, fork, and organization/repo interactions.

3. **Use the commits API per repo for real commit messages**
   - Discover candidate repos from `users/benelog/repos?sort=updated&direction=desc` and public events.
   - For each candidate repo and date range:
     ```text
     GET /repos/{owner}/{repo}/commits?since={utc_start}&until={utc_end}&per_page=100
     ```
   - Filter to commits authored/committed by the user when possible (`author.login == benelog`, `committer.login == benelog`, or matching author email/name when API login is absent).
   - Beware repositories where `updated_at` is old but `pushed_at` is inside the range; include both fields in candidate selection.

4. **Include cross-repo actions**
   - Public events may show activity in upstream repos such as `spring-projects/spring-framework` even when the main commit data is under `benelog/*`.
   - Include these as their own repo if the user said "Github.com에서 한일" rather than "내 repos".

5. **Prepare issue content**
   - Title pattern:
     - `YYYY-MM-DD~YYYY-MM-DD {owner/repo} 작업 기록`
     - or a concise Korean summary if one repo has a clear theme.
   - Body should include:
     - 기간
     - 저장소 link
     - 핵심 작업 요약 bullets
     - 주요 커밋/이슈/PR 링크 bullets
     - source note such as `GitHub API 기준, KST 날짜 범위로 집계`.

## GitHub issue API workflow

When `gh` is installed and authenticated, prefer it for readability:

```bash
gh issue create \
  --repo benelog/personal-task \
  --title '2026-09-21~2026-09-28 benelog/blog 작업 기록' \
  --body-file /tmp/issue-body.md \
  --milestone '23'

gh issue close ISSUE_URL --repo benelog/personal-task --comment '기간 작업 기록으로 등록 완료.'
```

When `gh` is unavailable, use REST API with a token:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/benelog/personal-task/issues \
  -d @payload.json
```

Payload shape:

```json
{
  "title": "2026-09-21~2026-09-28 benelog/blog 작업 기록",
  "body": "...",
  "milestone": 23
}
```

Then close:

```bash
curl -sS -X PATCH \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/benelog/personal-task/issues/ISSUE_NUMBER \
  -d '{"state":"closed"}'
```

## Pitfalls

- Do not stop after producing a plan or draft list if authentication exists; actually create and close the issues, then report issue URLs.
- Do not use `git` commands for issue operations; `git` and GitHub Issues are separate APIs.
- Do not rely only on public events; they can miss older activity and have incomplete push commit details.
- Do not treat missing `gh` CLI as a blocker if a GitHub token is available; REST API with `curl` is enough.
- Do not store GitHub tokens in memory or skills. Use environment variables or user-provided one-time credentials only for the active task.

## References

- `references/2026-09-github-activity-issue-session.md` — notes from the session that prompted this skill, including auth distinction and API gathering lessons.
