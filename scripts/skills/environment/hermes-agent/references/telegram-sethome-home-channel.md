# Telegram `/sethome` home-channel prompt

## Symptom

On a new bot boot or fresh Telegram session, Hermes sends an onboarding message similar to:

> No home channel is set for Telegram. A home channel is where Hermes delivers cron job results and cross-platform messages. Type /sethome to make this chat your home channel, or ignore to skip.

## Root cause found in Hermes Agent 0.12.0

In `gateway/run.py`, the prompt is sent when all of these are true:

- There is no existing history for the session.
- The source platform is not local or webhook.
- The environment variable `<PLATFORM>_HOME_CHANNEL` is not set.

For Telegram, the key is:

```text
TELEGRAM_HOME_CHANNEL
```

The gateway config loader (`gateway/config.py`) reads `TELEGRAM_HOME_CHANNEL` and `TELEGRAM_HOME_CHANNEL_NAME` from environment variables loaded from `~/.hermes/.env`.

## Fix options

### Preferred user-facing fix

Run `/sethome` in the desired Telegram chat.

### Direct file fix

Add these to `~/.hermes/.env`:

```dotenv
TELEGRAM_HOME_CHANNEL="<chat_id>"
TELEGRAM_HOME_CHANNEL_NAME="<human readable chat name>"
```

A chat ID can be found from `~/.hermes/channel_directory.json` or from `send_message(action="list")` if the send-message tool is available.

## Verification

Run:

```bash
hermes status | sed -n '/◆ Messaging Platforms/,+18p'
```

Expected output includes:

```text
Telegram      ✓ configured (home: <chat_id>)
```

Also run:

```bash
hermes config check
```

## Pitfall observed

`hermes config set TELEGRAM_HOME_CHANNEL <chat_id>` writes an uppercase root key to `~/.hermes/config.yaml`. In the observed installation this was not the canonical place for the gateway home-channel setting. Remove those root keys if created and write the values to `~/.hermes/.env` instead.

## Restart caveat

In a manual-process/Modal-style gateway, `hermes gateway restart` may time out. Do not retry blindly. Check:

```bash
hermes status | sed -n '/◆ Gateway Service/,+8p'
```

Then report whether the service is still running and whether a safe external restart is needed.
