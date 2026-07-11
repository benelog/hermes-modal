# Cloud deployment free-tier research notes

Use when updating or fact-checking `benelog/devnote/content/cloud-deployment.md`, especially the Koyeb/Fly.io/Render/Vercel/Modal/ngrok/Cloud Run/DigitalOcean/Coolify comparison.

## Public page

- `content/cloud-deployment.md` deploys to `https://devnote.benelog.net/cloud-deployment`.
- When citing the local markdown or a GitHub blob line, include the deployed URL for the user.

## Free tier / payment-method facts captured in the 2026-07-11 session

Treat these as starting points only: re-check official pricing/docs before editing because free tiers change frequently.

| Service | Payment method / credit card | Free quota to verify |
| --- | --- | --- |
| Coolify | Self-hosted Coolify itself does not require a card; hosting server may. | Self-hosted is free forever; Coolify Cloud is paid. |
| DigitalOcean App Platform | Account billing generally requires a payment method. | Free tier is for static sites: 3 apps, 1 GiB transfer per app; web services/workers/jobs are paid. |
| Google Cloud Run | Google Cloud Free Trial/Free Tier signup requires a valid card/payment method. | Cloud Run free tier: 2M requests/month, 360,000 GiB-seconds memory, 180,000 vCPU-seconds, 1 GiB outbound data from North America/month. New accounts may also receive $300/90-day credits. |
| ngrok | HTTP free usage can start without a paid plan; some features such as TCP endpoints mention credit-card verification. | Free plan: $5 one-time usage credit, up to 3 online endpoints, 1 GB data transfer, 20k HTTP/S requests. |
| Koyeb | Official FAQ says Koyeb requires a credit card to prevent fraud/abuse and uses a $29 pre-authorization hold. | One free web service in Frankfurt or Washington, D.C.: 512MB RAM, 0.1 vCPU, 2GB SSD; one free PostgreSQL database limited to 5 active hours and 1GB storage; outbound bandwidth note observed as 100GB/mo free before future charging. |
| Fly.io | Short free trial can begin before adding a payment method; normal org usage requires a credit card on file. | Free trial: 2 total VM hours or 7 days, whichever comes first; trial machines auto-stop after 5 minutes. Legacy free allowances only apply to old plans. |
| Render | Free web services/datastores can be deployed without selecting a paid plan; adding a card triggers a small verification transaction. | Free web service: 512MB RAM, 0.1 CPU; 750 free instance hours/workspace/month; spins down after 15 minutes idle; Free Postgres expires after 30 days. |
| Vercel | Hobby plan generally does not require a card; Pro/paid usage requires billing. | Hobby includes Edge Requests 1M/month, Fast Data Transfer 100GB/month, Function Invocations 1M/month, Active CPU 4 CPU-hours, Provisioned Memory 360 GB-hours. Check commercial-use restrictions. |
| Modal | Pricing page observed Starter plan, but card requirement was not confirmed from official pricing page. | Starter: $30/month free credits, 3 workspace seats, 100 containers + 10 GPU concurrency, limited Scheduled/Web Functions. |

## Research workflow

1. Start from the referenced markdown/GitHub line and identify the exact service list plus deployed slug.
2. Fetch official pricing/docs pages first; use Jina Reader (`https://r.jina.ai/http://https://...`) when JS-heavy pricing pages are hard to parse.
3. Search/snippet for: `credit card`, `payment method`, `free tier`, `free trial`, `included`, `requests`, `bandwidth`, `hours`, `GB-hours`, `vCPU-seconds`.
4. Mark uncertain items explicitly instead of inferring card requirements from pricing tables alone.
5. If editing the repo, update claims that changed materially (e.g. Koyeb's free web service specs) and run the normal knowledge-repo verification/commit flow.
