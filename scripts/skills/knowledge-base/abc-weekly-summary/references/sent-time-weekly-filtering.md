# Sent-time weekly filtering for ABC summaries

## Session learning

The user explicitly corrected the ABC weekly summary workflow: weekly summaries must be based on when the original KakaoTalk sender sent the message, not when the Modal server received/collected it.

## Durable rule

For weekly date windows, never filter by `received_at`. `received_at` is the Modal server collection timestamp and can include old KakaoTalk messages that were only scrolled/collected later.

Use this date source order:

1. If `sent_time` contains an ISO date or datetime, use `sent_time[:10]`.
2. Otherwise, for legacy records, use `client_time[:10]`; in the current collector schema this is the KakaoTalk-displayed original send date.
3. If neither field has an ISO date, exclude the message from explicit weekly date filtering rather than guessing from `received_at`.

## Helper script pattern

`abc_book_enrichment.py` should expose a helper equivalent to:

```python
def message_sent_date(message):
    sent_time = str(message.get("sent_time") or "").strip()
    if re.match(r"^\d{4}-\d{2}-\d{2}", sent_time):
        return sent_time[:10]
    client_time = str(message.get("client_time") or "").strip()
    if re.match(r"^\d{4}-\d{2}-\d{2}", client_time):
        return client_time[:10]
    return ""
```

Then filter with:

```python
start <= message_sent_date(m) <= end
```

## Verification command

After changing the script, verify JSON validity and count for an explicit week:

```bash
python -m py_compile /root/.hermes/scripts/abc_book_enrichment.py
python /root/.hermes/scripts/abc_book_enrichment.py \
  --since 9day \
  --start YYYY-MM-DD \
  --end YYYY-MM-DD \
  --max-output-chars 2000 | python -m json.tool >/tmp/abc_test_formatted.json
```

The output should show the intended `start_date`, `end_date`, and a plausible `message_count` without relying on Modal collection dates.
