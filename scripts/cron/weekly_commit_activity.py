#!/usr/bin/env python3
"""Collect recent Git commit activity for weekly learning/activity briefing.

Outputs compact Markdown context for a Hermes cron prompt.
"""
from __future__ import annotations

import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOTS = [Path('/root/workspace')]
EXCLUDE_PARTS = {'.cache', '.npm', 'node_modules', '.venv', 'venv', '__pycache__', 'output', 'public'}
SINCE = os.environ.get('WEEKLY_COMMIT_SINCE', '1 week ago')
MAX_COMMITS_PER_REPO = int(os.environ.get('WEEKLY_COMMIT_MAX_COMMITS_PER_REPO', '30'))
MAX_ADDED_LINES_PER_COMMIT = int(os.environ.get('WEEKLY_COMMIT_MAX_ADDED_LINES_PER_COMMIT', '30'))
MAX_LINE_LEN = int(os.environ.get('WEEKLY_COMMIT_MAX_LINE_LEN', '260'))


def run(repo: Path, args: list[str], timeout: int = 120) -> str:
    proc = subprocess.run(
        ['git', '-c', 'core.quotePath=false', '-C', str(repo), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
    )
    if proc.returncode != 0:
        return f'[git error: {proc.stderr.strip()}]'
    return proc.stdout


def discover_repos() -> list[Path]:
    repos: list[Path] = []
    seen: set[Path] = set()
    for root in ROOTS:
        if not root.exists():
            continue
        for gitdir in root.rglob('.git'):
            if any(part in EXCLUDE_PARTS for part in gitdir.parts):
                continue
            repo = gitdir.parent.resolve()
            if repo not in seen:
                seen.add(repo)
                repos.append(repo)
    return sorted(repos, key=lambda p: str(p))


def deployed_url(repo_name: str, file_path: str) -> str | None:
    path = Path(file_path)
    stem = path.stem
    if repo_name == 'devnote' and file_path.startswith('content/') and path.suffix == '.md':
        return f'https://devnote.benelog.net/{stem}'
    if repo_name == 'wiki' and file_path.startswith('content/') and path.suffix == '.md':
        return f'https://wiki.benelog.net/{stem}'
    if repo_name == 'blog' and file_path.startswith('src/content/') and path.suffix == '.adoc':
        return f'https://blog.benelog.net/{stem}.html'
    if repo_name == 'bookshelf-it' and file_path.startswith('content/post/') and path.suffix == '.md':
        return f'https://bookshelf-it.benelog.net/{stem}/'
    if repo_name == 'bookshelf' and file_path.startswith('content/post/') and path.suffix == '.md':
        return f'https://bookshelf.benelog.net/{stem}/'
    if repo_name == 'diary' and file_path.startswith('content/post/') and path.suffix == '.md':
        return f'https://diary.benelog.net/{stem}/'
    return None


def added_line_excerpt(repo: Path, commit: str) -> list[str]:
    show = run(repo, ['show', '--format=', '--unified=3', '--no-ext-diff', '--find-renames', commit], timeout=180)
    lines: list[str] = []
    current_file = ''
    for raw in show.splitlines():
        if raw.startswith('+++ b/'):
            current_file = raw[6:]
        elif raw.startswith('diff --git'):
            current_file = ''
        elif raw.startswith('+') and not raw.startswith('+++'):
            text = raw[1:].strip()
            if not text:
                continue
            # Skip low-signal markup/config noise but keep headings and prose.
            if text in {'---', '+++'}:
                continue
            if len(text) > MAX_LINE_LEN:
                text = text[:MAX_LINE_LEN].rstrip() + '…'
            prefix = f'{current_file}: ' if current_file else ''
            lines.append(prefix + text)
            if len(lines) >= MAX_ADDED_LINES_PER_COMMIT:
                break
    return lines


def main() -> None:
    print(f'# Weekly Git activity context')
    print(f'- generated_at_utc: {datetime.now(timezone.utc).isoformat()}')
    print(f'- since: {SINCE}')
    print(f'- roots: {", ".join(str(r) for r in ROOTS)}')
    print()

    any_commit = False
    for repo in discover_repos():
        name = repo.name
        # Fetch remote refs without changing working tree. Ignore network/auth failures but surface them.
        fetch = subprocess.run(
            ['git', '-C', str(repo), 'fetch', '--all', '--prune'],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=180,
        )
        fetch_warning = fetch.stderr.strip() if fetch.returncode != 0 else ''

        log = run(repo, [
            'log', '--all', f'--since={SINCE}', f'-n{MAX_COMMITS_PER_REPO}',
            '--date=short', '--pretty=format:%h%x09%ad%x09%an%x09%s'
        ])
        commits = [line for line in log.splitlines() if line.strip() and not line.startswith('[git error:')]
        if not commits:
            continue
        any_commit = True
        print(f'## {name}')
        print(f'- path: {repo}')
        if fetch_warning:
            print(f'- fetch_warning: {fetch_warning}')
        for row in commits:
            try:
                h, date, author, subject = row.split('\t', 3)
            except ValueError:
                print(f'- raw_log: {row}')
                continue
            print(f'### {h} — {date} — {subject}')
            print(f'- author: {author}')
            files = [f for f in run(repo, ['show', '--format=', '--name-only', '--find-renames', h]).splitlines() if f.strip()]
            if files:
                print('- files:')
                for f in files[:12]:
                    url = deployed_url(name, f)
                    if url:
                        print(f'  - {f} ({url})')
                    else:
                        print(f'  - {f}')
                if len(files) > 12:
                    print(f'  - ... and {len(files) - 12} more')
            excerpts = added_line_excerpt(repo, h)
            if excerpts:
                print('- added_excerpt:')
                for line in excerpts:
                    print(f'  - {line}')
        print()

    if not any_commit:
        print('No commits found in the last week.')


if __name__ == '__main__':
    main()
