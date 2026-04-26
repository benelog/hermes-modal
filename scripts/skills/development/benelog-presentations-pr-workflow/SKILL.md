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

   ```bash
   git push -u origin <branch>
   ```

   If `gh` is installed and authenticated:

   ```bash
   gh pr create --base main --head <branch> --title "..." --body "..."
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
