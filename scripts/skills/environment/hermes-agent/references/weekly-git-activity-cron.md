# Weekly Git activity / learning briefing cron

Session-derived pattern for adding a Hermes cron job that reports the user's weekly learning/activity from Git commit logs.

## Trigger

Use when the user asks for a recurring report such as:

- “매주 토요일 오전 7시에 나의 1주일 동안의 commit log를 바탕으로 학습/활동 내역을 보고해줘”
- weekly/monthly Git activity summaries from the user's knowledge/code repositories

## Runtime cron pattern

1. Create a pre-run script in `~/.hermes/scripts/`, e.g. `weekly_commit_activity.py`.
2. The script should:
   - discover Git repos under `/root/workspace` (not only `/root/workspace/benelog`);
   - run `git fetch --all --prune` without changing worktrees;
   - use `git log --all --since='1 week ago'` so remote-only refs are included;
   - include commit hash/date/author/subject, changed files, and concise added-line excerpts;
   - run Git with `-c core.quotePath=false` so Korean filenames are readable;
   - include deployed URLs for benelog repos when derivable:
     - `devnote/content/<slug>.md` → `https://devnote.benelog.net/<slug>`
     - `wiki/content/<slug>.md` → `https://wiki.benelog.net/<slug>`
     - `blog/src/content/<slug>.adoc` → `https://blog.benelog.net/<slug>.html`
     - `bookshelf-it/content/post/<slug>.md` → `https://bookshelf-it.benelog.net/<slug>/`
3. Create the job with schedule `0 22 * * 5` for Saturday 07:00 KST.
4. Enable the `terminal` toolset if the prompt may need date checks or additional Git inspection.

## Prompt guidance

The prompt should ask for Korean Markdown, no tables, and synthesis by learning/activity themes rather than a raw commit list. Include sections for:

- period and repository/commit count;
- 4–8 synthesized learning/activity themes;
- non-learning operational work (site UI, Hermes automation, repo maintenance);
- a short final “이번 주 한 줄 요약”;
- a `참고` section for fetch warnings or incomplete repos.

Explicitly say not to use the 장창모-only greeting for scheduled outputs.

## Git-tracked Modal deployment mirror

For material cron additions in this Modal setup, mirror the runtime job to `https://github.com/benelog/hermes-modal` by default:

- `cron_jobs/weekly-git-activity.json` with sanitized declarative fields only;
- `scripts/cron/weekly_commit_activity.py` so `scripts/prepare_runtime.py` can ship it into `~/.hermes/scripts/`;
- `cron_jobs/README.md` entry describing schedule and script.

Verify:

```bash
python -m py_compile scripts/apply_cron_jobs.py scripts/cron/weekly_commit_activity.py
python scripts/apply_cron_jobs.py cron_jobs/weekly-git-activity.json --dry-run
git diff --check
```

Do not commit runtime fields such as Telegram chat IDs, next/last run timestamps, status, delivery errors, session/output files, or credentials.

## Push pitfall

If `git push origin main` fails or hangs because credentials are unavailable, read `GITHUB_TOKEN` from `~/.hermes/.env` without printing it and push using a temporary credential mechanism or tokenized remote URL. Redact token values from all logs/output. Verify with `git status --short`, `git rev-parse --short HEAD`, and remote branch state after pushing.
