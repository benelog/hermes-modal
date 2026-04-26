---
name: benelog-presentations-pr-workflow
description: Edit benelog/presentations Marp slide decks and prepare a pull request, including repo conventions, build verification, and fallback when GitHub push auth is unavailable.
---

# Benelog presentations PR workflow

## When to use

Use when the user asks to edit, review, improve, or send a PR for a deck under `benelog/presentations`, especially URLs like:

- `https://benelog.github.io/presentations/<YYYYMMDD-slug>/`
- `https://github.com/benelog/presentations/...`

## Repository facts

- Repo: `https://github.com/benelog/presentations`
- Each deck lives at `YYYYMMDD-slug/slides.md`.
- Source slides are Marp Markdown.
- GitHub Actions builds HTML/PDF and deploys GitHub Pages on `main`.
- Do **not** commit `dist/`; it is a build artifact.
- If `npm install` creates an untracked `package-lock.json` and it was not already tracked, do not include it unless the user explicitly asked for dependency lock changes.

## Important convention

The repo's `CLAUDE.md` says Korean typo preservation matters: **content edits and typo corrections should be separate commits**.

When making both kinds of changes:

1. Make typo/spacing/link-text fixes first.
2. Commit them separately, e.g. `Fix typos in AI agent presentation`.
3. Then make structural/content additions.
4. Commit them separately, e.g. `Add practical guidance to AI agent presentation`.

## Procedure

1. Clone or open the repo.

   ```bash
   git clone https://github.com/benelog/presentations.git /root/workspace/presentations
   cd /root/workspace/presentations
   git status --short --branch
   ```

2. Read local guidance before editing.
   - Check `CLAUDE.md` or other repo instructions if the tool injects them or if present in the repo.

3. Create a feature branch.

   ```bash
   git checkout -b <descriptive-branch-name>
   ```

4. Edit the relevant `YYYYMMDD-slug/slides.md`.
   - Use Marp slide separators: a single `---` line.
   - Keep images in the same deck directory and reference with `![](file.png)`.

5. Commit typo-only edits separately from content edits.

   ```bash
   git add YYYYMMDD-slug/slides.md
   git commit -m "Fix typos in ... presentation"
   # then content edits
   git add YYYYMMDD-slug/slides.md
   git commit -m "Add ... to ... presentation"
   ```

6. Verify the build.

   ```bash
   npm install
   npm run build
   ```

   Notes:
   - The HTML build can succeed even if PDF conversion reports a browser error.
   - In Modal/Hermes environments Chrome/Firefox may be missing, causing Marp PDF conversion to print `No suitable browser found`.
   - `scripts/build.sh` currently runs PDF generation with `|| true`, so PDF failure does not necessarily fail the overall build.
   - Report this nuance clearly in the final response.

7. Clean untracked build/dependency artifacts unless intentionally part of the change.

   ```bash
   git status --short
   rm package-lock.json   # only if newly created and not meant to be committed
   ```

8. Push and open a PR if GitHub auth is available.

   First check both live environment variables and the Hermes dotenv file. In Modal/Hermes, `GITHUB_TOKEN` may exist in `~/.hermes/.env` even when it is not exported to the process environment.

   ```bash
   python - <<'PY'
from pathlib import Path
for line in Path.home().joinpath('.hermes/.env').read_text(errors='ignore').splitlines():
    if line.startswith('GITHUB_TOKEN='):
        print('GITHUB_TOKEN found in ~/.hermes/.env')
        break
PY
   ```

   If normal push works, use it:

   ```bash
   git push -u origin <branch>
   ```

   If `git push` over HTTPS fails for lack of credentials but `GITHUB_TOKEN` is present in `~/.hermes/.env`, read it without printing the secret and push with an authenticated URL:

   ```bash
   set +x
   TOKEN=$(python - <<'PY'
from pathlib import Path
for line in Path.home().joinpath('.hermes/.env').read_text(errors='ignore').splitlines():
    if line.startswith('GITHUB_TOKEN='):
        print(line.split('=', 1)[1].strip().strip('"\''))
        break
PY
)
   git push -u "https://x-access-token:${TOKEN}@github.com/benelog/presentations.git" <branch>
   ```

   If `gh` is installed and authenticated:

   ```bash
   gh pr create --base main --head <branch> --title "..." --body "..."
   ```

   If `gh` is unavailable but `GITHUB_TOKEN` is present, create the PR with the GitHub REST API. Write the JSON body to a temp file to avoid shell quoting issues; write the response to a file before parsing it.

   ```bash
   python - <<'PY' > /tmp/pr_body.json
import json
print(json.dumps({
  "title": "Improve AI agent presentation",
  "head": "<branch>",
  "base": "main",
  "body": "## Summary\n- ...\n\n## Verification\n- `npm run build`\n",
}))
PY
   curl -sS -o /tmp/pr_response.json -w '%{http_code}\n' -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "Accept: application/vnd.github+json" \
     -H "X-GitHub-Api-Version: 2022-11-28" \
     https://api.github.com/repos/benelog/presentations/pulls \
     --data @/tmp/pr_body.json > /tmp/pr_status.txt
   ```

   If the create call returns 422 because a PR already exists for the branch, list the existing PR instead of treating it as failure:

   ```bash
   curl -sS -H "Authorization: Bearer $TOKEN" \
     -H "Accept: application/vnd.github+json" \
     'https://api.github.com/repos/benelog/presentations/pulls?head=benelog:<branch>&state=open'
   ```

## Fallback when GitHub push auth is unavailable

If pushing fails with something like:

```text
fatal: could not read Username for 'https://github.com': No such device or address
```

then create a patch file and provide exact apply/push instructions:

```bash
git format-patch origin/main..HEAD --stdout > /root/workspace/<meaningful-name>.patch
git diff --stat origin/main..HEAD
```

Tell the user:

```bash
cd presentations
git checkout -b <branch>
git am /path/to/<meaningful-name>.patch
git push -u origin <branch>
```

Then give the compare URL:

```text
https://github.com/benelog/presentations/compare/main...<branch>
```

## Verification checklist

Before finalizing:

- `git status --short` is clean except intentional untracked artifacts, which should be removed or explained.
- `git log --oneline --decorate -3` shows the new commits.
- `npm run build` result is reported accurately.
- If PR could not be created, the patch file path and apply instructions are provided.
