---
name: skills-autocommit-hook-verification
description: Verify whether the Modal Hermes skills autocommit hook is committing and pushing skill changes to the hermes-modal git repo after agent turns.
---

# Skills Autocommit Hook Verification

## When to use

Use this when the user asks whether skill changes are being automatically committed/pushed, whether the `skills-autocommit` hook is working, or why a skill change did/did not appear in the `hermes-modal` repo.

## Key facts

- Runtime skills live under `/root/.hermes/skills`.
- The autocommit hook lives at `/root/.hermes/hooks/skills-autocommit/`.
- Hook config: `/root/.hermes/hooks/skills-autocommit/HOOK.yaml`.
- Hook handler: `/root/.hermes/hooks/skills-autocommit/handler.py`.
- Commit script: `/opt/hermes-modal/scripts/commit_skills.py`.
- Hook log: `/root/.hermes/logs/skills-autocommit.log`.
- The hook runs on `agent:end`, so skill changes are committed after the agent turn ends, not synchronously inside `skill_manage`.
- The script mirrors `/root/.hermes/skills` into `scripts/skills` in `https://github.com/benelog/hermes-modal.git`, commits with message `Sync skills from Modal volume`, pushes to `main`, and writes `/root/.hermes/skills/.last_committed_hash`.

## Verification procedure

1. Inspect hook registration:

```bash
cat /root/.hermes/hooks/skills-autocommit/HOOK.yaml
cat /root/.hermes/hooks/skills-autocommit/handler.py
```

Expected event:

```yaml
events:
  - agent:end
```

2. Inspect the commit script:

```bash
cat /opt/hermes-modal/scripts/commit_skills.py
```

Check that it:
- uses `HERMES_HOME/skills`, defaulting to `/root/.hermes/skills`
- skips files whose relative path contains dot-prefixed parts
- computes a SHA256 fingerprint
- clones `https://github.com/benelog/hermes-modal.git`
- copies skills to `scripts/skills`
- commits and pushes changes
- writes `.last_committed_hash`

3. Check recent hook output:

```bash
cat /root/.hermes/logs/skills-autocommit.log
```

Look for lines like:

```text
[main <sha>] Sync skills from Modal volume
pushed:
 M scripts/skills/...
 D scripts/skills/...
```

4. Verify the current fingerprint matches the last committed hash:

```bash
python3 - <<'PY'
from pathlib import Path
import hashlib
SKILLS=Path('/root/.hermes/skills')
last=(SKILLS/'.last_committed_hash').read_text().strip() if (SKILLS/'.last_committed_hash').exists() else ''
def ok(rel): return not any(p.startswith('.') for p in rel.parts)
h=hashlib.sha256()
for f in sorted([f for f in SKILLS.rglob('*') if f.is_file() and ok(f.relative_to(SKILLS))]):
    rel=f.relative_to(SKILLS).as_posix()
    h.update(rel.encode()); h.update(b'\0'); h.update(f.read_bytes()); h.update(b'\0')
cur=h.hexdigest()
print('current',cur)
print('last   ',last)
print('match  ',cur==last)
PY
```

`match True` means the current runtime skills match the most recent recorded autocommit state.

5. If `/tmp/hermes-modal-clone` exists, use it for an additional local check:

```bash
git -C /tmp/hermes-modal-clone log --oneline -5 -- scripts/skills | cat
git -C /tmp/hermes-modal-clone status --short --branch | cat
```

Then inspect whether the expected skill files are present/absent under:

```text
/tmp/hermes-modal-clone/scripts/skills/
```

## Interpreting results

- Working as intended:
  - `HOOK.yaml` includes `agent:end`
  - `skills-autocommit.log` shows recent commits/pushes
  - `.last_committed_hash` matches the freshly computed fingerprint
  - `/tmp/hermes-modal-clone` has the expected `scripts/skills` content

- No-op is expected when:
  - skills fingerprint is unchanged
  - `.last_committed_hash` already equals current fingerprint

- Important wording for the user:
  - Say “skill changes are committed/pushed after the agent turn via the `agent:end` hook,” not “`skill_manage` commits synchronously.”

## Pitfalls

- `/root/.hermes/skills` itself is not necessarily a git repo. Do not expect `git status` there to work.
- `git ls-remote https://github.com/benelog/hermes-modal.git` may fail without credentials; the hook uses `GITHUB_TOKEN` internally.
- If `GITHUB_TOKEN` is missing, `commit_skills.py` logs `skipped: GITHUB_TOKEN not set` and no push occurs.
- If `/root/.hermes/skills/.image-manifest.txt` is missing, the script logs that runtime preparation has not run and skips.
- The hook log may contain multiple clone lines because the hook can run every turn and serializes with a lock.
