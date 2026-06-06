# Devnote AI agent / harness placement notes

Session-derived placement guidance for adding AI agent platform, Managed Agents, or harness-engineering YouTube/articles to `benelog/devnote`.

## Useful target pages

- `content/ai-agent-harness.md`
  - Best fit for Claude/Anthropic Managed Agents, agent platform infrastructure, harness/model boundary, multi-agent orchestration, production reliability, outcome/budget framing, and terminal agent harnesses that combine TUI/CLI, chat-channel gateways, persistent memory, tools, and multi-agent/team orchestration.
  - The page currently has `## Harness` and `## Agent` sections. Place Managed Agents / platform interviews and terminal AI agent platforms under `## Agent` unless the source is specifically about harness construction patterns.
  - Example sources added here:
    - Every AI & I, “The Secrets of Claude's Agent Platform From the Team Who Built It” (`https://www.youtube.com/watch?v=lLypHkIVLqc`).
    - OpenCrabs landing page (`https://opencrabs.com/`): open-source terminal AI agent with TUI/CLI plus Telegram/Discord/Slack/WhatsApp/Trello channels, 50+ tools, persistent memory, Mission Control, multi-agent/team orchestration, self-healing, and RSI/self-improving features.
    - OpenClaw landing page (`https://openclaw.ai/`): personal AI assistant for email/calendar/flight check-in and chat-app operation; place under `## Agent` because it emphasizes persistent memory, comms integration, skills, 24/7 assistant behavior, and development workflow automation examples.
    - Hermes Agent landing page (`https://hermes-agent.nousresearch.com/`): server-resident autonomous agent with cross-platform gateways, persistent memory/skills, natural language cron, subagents, sandbox backends, and tool integrations; place under `## Agent`.
    - Pi Coding Agent (`https://pi.dev/`): minimal and extensible agent harness; place under `## Harness` because it focuses on changing/customizing the harness via extensions, skills, prompt templates, packages, context engineering, RPC, and SDK modes.
- `content/claude-code.md`
  - Better fit for Claude Code usage, plugins, token/cost optimization, session management, and Claude Code-specific workflows.
- `content/ai-coding-agent.md`
  - Better fit for AI coding agents, ADLC, tools, and software-development productivity cases.
- `content/ai-agent-role.md`
  - Better fit for developer role changes, AI-native engineer mindset, and organization/process impacts.

## Metadata extraction reminder

For YouTube sources, oEmbed + Jina Reader often provides enough grounded metadata even when transcript access is blocked. Prefer concise Korean bullets grounded in title, description, and chapter timestamps; mention transcript limitations in the final response if relevant.