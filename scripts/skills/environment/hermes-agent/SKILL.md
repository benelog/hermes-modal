---
name: hermes-agent
description: Configure, set up, modify, troubleshoot, and operate Hermes Agent itself, including CLI config, gateway platforms, Telegram home-channel behavior, tools, skills, memory, providers, and services.
---

# Hermes Agent

## When to use

Use this skill whenever the user asks about configuring, setting up, installing, enabling, disabling, modifying, or troubleshooting Hermes Agent itself, including:

- Hermes CLI commands such as `hermes config`, `hermes status`, `hermes gateway`, `hermes tools`, `hermes skills`, `hermes setup`
- Messaging gateway behavior for Telegram, Discord, Slack, WhatsApp, etc.
- Model/provider/auth configuration
- Tools, MCP servers, skills, hooks, memory, cron, sessions, profiles, or plugins
- Bot startup/boot messages, `/sethome`, home channels, pairing, allowlists, or gateway delivery

## Procedure

1. **Inspect live installation first**
   - Check the CLI exists and version/help as needed:
     ```bash
     which hermes
     hermes --help
     hermes version 2>/dev/null || hermes --version
     ```
   - Do not assume docs or package paths; inspect the live environment.

2. **Locate config and status**
   - Use:
     ```bash
     hermes config path
     hermes config show
     hermes status
     ```
   - Check `~/.hermes/config.yaml` for non-secret structured settings.
   - Check `~/.hermes/.env` for environment variables and secrets. When reporting, do not reveal full tokens.

3. **Understand whether a setting is config.yaml or .env based**
   - `hermes config set <key> <value>` writes to `config.yaml` and is appropriate for normal config keys.
   - Some gateway/runtime settings are still consumed from environment variables loaded from `~/.hermes/.env` (e.g., `TELEGRAM_HOME_CHANNEL`). Verify in the installed source before deciding where to write.
   - If `hermes config set` creates uppercase root keys that `hermes config check` does not recognize as schema keys, remove them from `config.yaml` and write the value to `.env` instead.

4. **For Telegram `/sethome` startup/onboarding messages**
   - Symptom: Hermes sends a message like “No home channel is set for Telegram… Type /sethome…” on first interaction after a fresh session or boot.
   - Cause: no `TELEGRAM_HOME_CHANNEL` environment variable is configured.
   - Preferred fixes:
     - In Telegram, run `/sethome` in the desired chat; or
     - Set `TELEGRAM_HOME_CHANNEL` and optionally `TELEGRAM_HOME_CHANNEL_NAME` in `~/.hermes/.env`.
   - Verify with:
     ```bash
     hermes status | sed -n '/◆ Messaging Platforms/,+18p'
     ```
     It should show `Telegram ✓ configured (home: <chat_id>)`.
   - See `references/telegram-sethome-home-channel.md` for the session-derived debugging path and pitfalls.

5. **Gateway service changes**
   - Check status before and after:
     ```bash
     hermes status | sed -n '/◆ Gateway Service/,+8p'
     ```
   - `hermes gateway restart` may hang in manual-process/Modal-style environments. If it times out, do not repeatedly retry; verify current status and report whether the running process will pick up the change or needs a safe external restart.

6. **Verification**
   - Run `hermes config check` for schema/config validation when editing config.
   - Run `hermes status` to verify platform/service behavior.
   - Inspect relevant logs under `~/.hermes/logs/` if status output is insufficient.

7. **Scripted cron jobs**
   - For cron jobs that need fresh computed context, random sampling, or repository reads before the model formats a message, place a pre-run script in `~/.hermes/scripts/` and configure the cron job with the script filename only.
   - The cronjob tool rejects absolute or home-relative script paths; use `script="name.py"`, not `/root/.hermes/scripts/name.py`.
   - See `references/scripted-cron-jobs.md` for the tested pattern and verification steps.

## Pitfalls

- Do not answer Hermes Agent setup/config questions from memory; inspect the live CLI and files.
- Do not expose secrets from `~/.hermes/.env`, `auth.json`, logs, or webhook tokens.
- Do not blindly use `hermes config set` for all uppercase environment variables; many are consumed from `.env`.
- If a mandatory skill named `hermes-agent` is missing, recreate this class-level umbrella skill rather than creating narrow one-off troubleshooting skills.
- Avoid repeated restart attempts after a timeout; verify status and explain the safe next step.
