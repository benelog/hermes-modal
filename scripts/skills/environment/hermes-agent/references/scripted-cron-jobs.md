# Scripted Hermes cron jobs

Session-derived notes for cron jobs that need fresh computed context before the model writes a scheduled message.

## Pattern

Use a pre-run script under `~/.hermes/scripts/` when the cron job needs deterministic data collection, random sampling, repository reads, or other context assembly before the LLM formats the result.

Example workflow:

1. Write a script in `~/.hermes/scripts/`, e.g. `english_random_expressions.py`, for the live runtime test.
2. If the cron job must be reproducible in the user's Modal deployment, also commit the canonical copy under `benelog/hermes-modal/scripts/cron/` and ensure `scripts/prepare_runtime.py` syncs `scripts/cron/` into `~/.hermes/scripts/` on startup/cron tick.
3. Make the script print JSON to stdout. Include source URLs and enough fields for the prompt to format without guessing.
4. For repo-backed sources, prefer a local shallow clone with `git fetch --prune`, `git checkout <branch>`, and `git pull --ff-only origin <branch>` on every run when the user expects fresh repository contents. Include a JSON flag or README note such as `git_pull_each_run: true` so the freshness behavior is auditable.
5. Create the cron job with `script` set to the filename only, not an absolute path.
6. Manually test with `hermes cron run <job_id>` or the cronjob tool, then `hermes cron tick`.
7. Verify with `hermes cron list` and inspect `~/.hermes/cron/output/<job_id>/`.

## Important quirk

The cronjob tool rejects absolute or home-relative script paths:

```text
Script path must be relative to ~/.hermes/scripts/. Got absolute or home-relative path: '/root/.hermes/scripts/english_random_expressions.py'. Place scripts in ~/.hermes/scripts/ and use just the filename.
```

Use:

```text
script="english_random_expressions.py"
```

not:

```text
script="/root/.hermes/scripts/english_random_expressions.py"
```

## Example: random entries from a GitHub Pages docs repo

For a daily random briefing from a static site whose source is in GitHub:

- Fetch the repo zip or clone/pull the repo in the pre-run script.
- Parse Markdown files under the published docs directory.
- Exclude index/lyrics or other non-expression pages if needed.
- Convert source files to deployed URLs, e.g. `docs/expressions/body.md` → `https://english.benelog.net/expressions/body.html`.
- Print JSON like:

```json
{
  "generated_at_kst": "2026-05-23T06:35:32+09:00",
  "source_repo": "https://github.com/benelog/english/tree/main/docs",
  "site": "https://english.benelog.net/",
  "count_available": 148,
  "items": [
    {
      "expression": "perspire",
      "notes": ["If people didn't perspire they'd die."],
      "page_title": "Body",
      "section": "Body",
      "source_file": "expressions/body.md",
      "url": "https://english.benelog.net/expressions/body.html"
    }
  ]
}
```

Keep the cron prompt strict: format only the JSON fields, cite the deployed URL for each item, and avoid inventing explanations beyond the provided notes.
