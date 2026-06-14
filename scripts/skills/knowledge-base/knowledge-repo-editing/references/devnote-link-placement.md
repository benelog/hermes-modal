# Devnote link placement notes

Use when adding development-related tools, papers, or GitHub projects to `/root/workspace/benelog/devnote`.

## Observed target pages and placement

- `content/markdown.md` → `## 변환`
  - Best fit for Markdown processing/conversion/query/rendering tools, including CLI/TUI Markdown readers.
  - Example: `https://mqlang.org/` (`mq`) was added here as a Rust CLI that processes Markdown with jq-like syntax.
  - Example: `https://github.com/charmbracelet/glow` (`Glow`) was added here as a Go CLI/TUI that renders Markdown in the terminal, discovers local/Git-repo Markdown files, and supports file/stdin/GitHub/HTTP Markdown inputs.
  - Include the GitHub URL as a nested bullet when the landing page exposes a separate repo; for GitHub-project URLs, the top-level link can be the repo itself.

- `content/ai-productivity.md` → `## 사례/연구`
  - Best fit for research reports/papers and engineering-organization case studies about AI coding tools, developer productivity, task-level vs organization-level output, DORA/PR/commit/release effects, AI coding measurement, and bottlenecks shifting from coding to review/decision-making.
  - Example: NBER Working Paper 35275, “Writing Code vs. Shipping Code: Productivity Effects Across Generations of AI Coding Tools,” was added here.
  - Example: Spotify Engineering’s “Coding Is No Longer the Constraint” was added here because it reports AI coding adoption/PR-frequency metrics, Fleet Management/Honk agent automation, Backstage/Soundcheck DevEx guardrails, and review/prioritization as the new bottleneck.
  - For NBER papers, prefer the PDF link if the user provided it, but also include DOI as a nested bullet when available. NBER metadata can be extracted from `https://www.nber.org/papers/<id>` via citation meta tags; Jina Reader works for both paper pages and PDFs.
  - If a news article is secondary coverage of a paper/report already listed, add it as a nested `국문 기사:` or similar source-language note under the existing primary-source bullet instead of creating a duplicate top-level item.

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
- AI Times article pages: direct scraping may require extra dependencies or return noisy chrome; Jina Reader with the original URL form `https://r.jina.ai/http://https://www.aitimes.com/news/articleView.html?idxno=...` can extract the title, published time, and body. Use the original AI Times URL in the note, not the Jina URL.
- Devnote deployed URL mapping: `content/<slug>.md` → `https://devnote.benelog.net/<slug>`; verify with `curl -I -L` when reporting it.
