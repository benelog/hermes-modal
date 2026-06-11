# Devnote link placement notes

Use when adding development-related tools, papers, or GitHub projects to `/root/workspace/benelog/devnote`.

## Observed target pages and placement

- `content/markdown.md` → `## 변환`
  - Best fit for Markdown processing/conversion/query tools.
  - Example: `https://mqlang.org/` (`mq`) was added here as a Rust CLI that processes Markdown with jq-like syntax.
  - Include the GitHub URL as a nested bullet when the landing page exposes a repo.

- `content/ai-productivity.md` → `## 사례/연구`
  - Best fit for research reports/papers about AI coding tools, developer productivity, task-level vs organization-level output, DORA/PR/commit/release effects, and AI coding measurement.
  - Example: NBER Working Paper 35275, “Writing Code vs. Shipping Code: Productivity Effects Across Generations of AI Coding Tools,” was added here.
  - For NBER papers, prefer the PDF link if the user provided it, but also include DOI as a nested bullet when available. NBER metadata can be extracted from `https://www.nber.org/papers/<id>` via citation meta tags; Jina Reader works for both paper pages and PDFs.

- `content/code-review.md` → `## 도구`
  - Best fit for PR review/code review tools, including AI/agentic PR reviewers.
  - Example: `https://github.com/Agent-Field/pr-af` was added here as an AgentField-based open-source agentic PR reviewer.
  - Summarize concrete review architecture/usage: dynamic per-PR strategy, parallel reviewer agents, AST/evidence grounding, falsifiability gates, GitHub Actions/API/CLI trigger, and any stated cost/runtime caveats.

- `content/ai-agent.md` → `## 도구`
  - Better fit for general AI agents and agent tools not specifically about code review or harness design.

- `content/ai-agent-harness.md`
  - Better fit for harness engineering, agent platform infrastructure, and agent orchestration patterns; see `references/devnote-agent-harness-placement.md` for details.

## Retrieval tips

- GitHub repo pages: use GitHub API `/repos/{owner}/{repo}` for description/language/stars plus raw `README.md` for grounded summary. If the README is long, extract only relevant sections such as “How It Works,” “Quick Start,” and “GitHub Actions Integration.”
- PDF papers: if local `pdftotext`/Python PDF libraries are unavailable, use Jina Reader on the PDF URL (`https://r.jina.ai/http://https://...pdf`) or the canonical abstract page.
- Devnote deployed URL mapping: `content/<slug>.md` → `https://devnote.benelog.net/<slug>`; verify with `curl -I -L` when reporting it.
