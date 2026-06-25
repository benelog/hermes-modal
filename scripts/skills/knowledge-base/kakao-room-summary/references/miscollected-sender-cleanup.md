# KakaoTalk miscollected sender cleanup

Use when the user confirms that specific `sender`-attributed messages are accessibility miscollection duplicates and asks to delete them from the collector store.

## Pattern

1. Fetch the relevant window with the helper and identify exact text duplicates where the suspect sender has the same body as another sender nearby.
2. Only delete after the user confirms the suspect records are not that sender's messages.
3. The Modal collector store is `modal.Dict` named `kakao-collect`. Keys are not exposed by `kakao_fetch.py`, so iterate the dict directly in a Python script when running inside the Modal/Hermes runtime.
4. Match narrowly: at least `rec['sender'] == <suspect sender>` and `rec['text'] in bad_text_set`; include room and received_at checks when available.
5. Delete entries with `d.pop(key, None)`, not `d.delete(key)`. In current Modal, `Dict.delete` is for deleting a Dict object by name and can treat the message key as a dict name.
6. Verify by re-running `python /root/.hermes/scripts/kakao_fetch.py --since <window>` and counting the suspect sender records plus confirming the bad texts are gone.

## Minimal script shape

```python
import modal
bad_set = {"exact bad message body", ...}
d = modal.Dict.from_name('kakao-collect', create_if_missing=False)
matched = []
for key, rec in list(d.items()):
    if rec.get('sender') == '정상혁' and rec.get('text') in bad_set:
        matched.append((key, rec.get('received_at')))
for key, _ts in matched:
    d.pop(key, None)
print('deleted', len(matched))
```

Keep the deletion report concise: deleted count, verification count, and what category of bad records was removed. Do not save the specific message texts as durable memory.