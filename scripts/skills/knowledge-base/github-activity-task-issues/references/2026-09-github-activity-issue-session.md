# 2026-09 GitHub activity issue session notes

## User request pattern

The user asked to take GitHub.com activity for two date ranges and register it in `benelog/personal-task` as one issue per repository under specific milestone URLs, then close each issue immediately:

- `2026-09-21` through `2026-09-28` → `https://github.com/benelog/personal-task/milestone/23`
- `2026-09-06` through `2026-09-20` → `https://github.com/benelog/personal-task/milestone/22`

## Technique discovered

Public GitHub events are useful but incomplete for this task:

- `/users/benelog/events/public` returned recent issue/comment/push events but did not provide complete push commit messages.
- Some `PushEvent` payloads had empty commit details.
- The commits API by repo was needed to recover useful commit summaries:
  - `/repos/{owner}/{repo}/commits?since={utc_start}&until={utc_end}&per_page=100`
- Candidate repo discovery should look at both `updated_at` and `pushed_at` from `/users/benelog/repos?sort=updated&direction=desc`.

## Workflow correction from user

When asked for a GitHub token, the user pointed out that previous devnote page additions had worked and asked whether those used `git` CLI. The distinction to preserve:

- Previous Markdown/repo edits can succeed via `git` commit/push credentials.
- GitHub issue creation, milestone assignment, and closing require GitHub API auth (`gh` or REST token).
- Do not imply that successful `git push` means issue API calls will work.

## Durable lesson

For future similar tasks, first perform the activity-gathering work that public APIs allow, but verify issue-write authentication separately before attempting side effects. If auth is absent, clearly explain the `git` vs GitHub Issues API boundary and ask for a token or configured `gh`/`GITHUB_TOKEN`.
