# Kakao collector data hygiene

Use this when the user reports that a KakaoTalk summary attributes messages to the wrong person, especially when messages appear as the user's/own Kakao nickname but the user says they did not say them.

## Symptom

Android Accessibility collection can misclassify a long left-side bubble as the user's own message if the bubble stretches close to the right edge. The same text may then appear twice in the server store: once under the wrong own-name sender and once under the real sender.

## Diagnosis pattern

1. Fetch a broad enough server window for the room, usually `14day` because the server retention is 14 days.
2. Group records by `(room, text)`.
3. For each group, if the sender set contains the user's own Kakao nickname and at least one other sender, treat the own-name copy as an Android accessibility miscapture candidate.
4. Show representative duplicates before deleting anything if the deletion scope is not obvious. If the user has explicitly asked to delete the miscaptured data, proceed with this narrow duplicate criterion.

## Server deletion pattern

The Modal store is `modal.Dict.from_name("kakao-collect")`. Keys are generated the same way as `scripts/kakao/collector_core.py::message_key`:

```python
import hashlib

def message_key(room: str, sender: str, text: str, client_time: str) -> str:
    raw = "\x01".join([room or "", sender or "", text or "", client_time or ""])
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()
    return f"{room}|{digest}"
```

For each bad own-name duplicate, compute the key from `room`, `sender`, `text`, and `client_time`, then `del kakao_dict[key]`. Verify by refetching the same window and confirming the duplicate-own-name count is zero.

## Android-side fix pattern

Do not classify a message as own solely by `rightMargin < leftMargin`. Long left-side messages can satisfy that condition. Add a helper that only returns own when both are true:

- the node is right-aligned (`rightMargin < leftMargin`), and
- its left boundary starts sufficiently far into the right side of the screen.

Keep this logic unit-tested with at least:

- a long left bubble that reaches the right edge is **not** own,
- a clearly right-aligned bubble is own,
- a normal left-aligned bubble is not own.

## Summary behavior while stale data remains

When duplicate text appears under multiple senders and one sender is the user/own nickname, do not claim the user said it. Prefer the non-own sender or mark it as duplicate/miscaptured data.
