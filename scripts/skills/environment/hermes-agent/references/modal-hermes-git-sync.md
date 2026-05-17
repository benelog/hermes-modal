# Modal Hermes git sync/push workflow

Session-derived workflow for committing live Modal Hermes deployment changes back to `benelog/hermes-modal`.

## Context

In the Modal Hermes environment, `/root/modal_app.py` may be the live/deployed working copy but `/root` itself is not necessarily a git repository. The canonical repository is:

```text
https://github.com/benelog/hermes-modal
```

A working clone can be created under `/root/workspace/hermes-modal`.

## Workflow

```bash
mkdir -p /root/workspace
if [ -d /root/workspace/hermes-modal/.git ]; then
  git -C /root/workspace/hermes-modal fetch origin
else
  git clone https://github.com/benelog/hermes-modal.git /root/workspace/hermes-modal
fi

cd /root/workspace/hermes-modal
cp /root/modal_app.py modal_app.py
python -m py_compile modal_app.py
git diff --check
git diff --stat
git diff -- modal_app.py
```

If the diff is correct:

```bash
git config user.name "Hermes Agent"
git config user.email "hermes-agent@users.noreply.github.com"
git add modal_app.py
git commit -m "Add Modal schedule for Hermes cron tick"
```

## Push with token from Hermes env

The environment may not expose `GITHUB_TOKEN` to the current shell, but `/root/.hermes/.env` can contain it. Do not print the token. Use `GIT_ASKPASS` so the token is not embedded in remotes or command history:

```bash
cat > /tmp/git-askpass-hermes.sh <<'SH'
#!/bin/sh
case "$1" in
  *Username*) printf '%s\n' x-access-token ;;
  *Password*) printf '%s\n' "$GITHUB_TOKEN" ;;
  *) printf '\n' ;;
esac
SH
chmod 700 /tmp/git-askpass-hermes.sh
set -a
. /root/.hermes/.env
set +a
GIT_ASKPASS=/tmp/git-askpass-hermes.sh GIT_TERMINAL_PROMPT=0 git push origin HEAD
rm -f /tmp/git-askpass-hermes.sh
```

Verify:

```bash
git log -1 --oneline --decorate
git ls-remote origin HEAD | sed -n '1p'
```

## Pitfalls

- `modal app history` may show a deployed version with an empty `Commit`; that means the deployed source was not necessarily tied to a git commit.
- `git push` over HTTPS can fail with `fatal: could not read Username for 'https://github.com': No such device or address` in the headless environment; use the `GIT_ASKPASS` method above.
- Never echo or include the GitHub token in the response, remote URL, logs, or commit message.
