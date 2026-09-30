# Devnote placement: agent skill repositories

When adding a GitHub repository that packages agent skills (for example Codex/Claude Code skills), prefer `devnote/content/ai-agent.md` under the `## 도구` → `### Skill` section.

## Source extraction pattern

Use GitHub API metadata plus README content:

- `https://api.github.com/repos/<owner>/<repo>` for description, license, stars, updated timestamp, and canonical URL.
- `https://api.github.com/repos/<owner>/<repo>/readme` and base64-decode `content` for README sections.
- Summaries should be grounded in the README: what the skills do, target agent runners, install commands, bundled scaffold/scripts/test hooks, and limitations such as optional API keys.

## Note style

Use one top-level bullet with the repository title linked to GitHub, then 3 concise nested Korean bullets:

1. what class of skill pack it is and which agent/runners it targets,
2. what runtime materials/scaffold/verification assets are included,
3. how to install or activate it, plus important caveats.

Keep the edit scoped to `content/ai-agent.md` unless a more specific existing page is clearly present.