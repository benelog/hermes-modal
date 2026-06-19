from pathlib import Path
import importlib.util
import unittest
from datetime import datetime, timedelta, timezone


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "kakao" / "collector_core.py"
spec = importlib.util.spec_from_file_location("collector_core", MODULE_PATH)
collector_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector_core)


class MessageKeyTests(unittest.TestCase):
    def test_same_message_same_key_regardless_of_received_time(self):
        a = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        b = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        self.assertEqual(a, b)

    def test_different_text_different_key(self):
        a = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        b = collector_core.message_key("아카라카북클럽", "홍길동", "잘가", "오후 3:25")
        self.assertNotEqual(a, b)

    def test_key_is_prefixed_with_room(self):
        key = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        self.assertTrue(key.startswith("아카라카북클럽|"))

    def test_field_boundary_is_unambiguous(self):
        # Fields are joined with a delimiter so shifting a character across a
        # field boundary still produces a different key (guards the dedupe id).
        a = collector_core.message_key("R", "ab", "c", "x")
        b = collector_core.message_key("R", "a", "bc", "x")
        self.assertNotEqual(a, b)


class ParseSinceTests(unittest.TestCase):
    def test_default_is_one_day(self):
        self.assertEqual(collector_core.parse_since_to_timedelta(None), timedelta(days=1))
        self.assertEqual(collector_core.parse_since_to_timedelta(""), timedelta(days=1))

    def test_days_variants(self):
        self.assertEqual(collector_core.parse_since_to_timedelta("3day"), timedelta(days=3))
        self.assertEqual(collector_core.parse_since_to_timedelta("2d"), timedelta(days=2))
        self.assertEqual(collector_core.parse_since_to_timedelta("5일"), timedelta(days=5))
        self.assertEqual(collector_core.parse_since_to_timedelta("7"), timedelta(days=7))

    def test_hours_variant(self):
        self.assertEqual(collector_core.parse_since_to_timedelta("12h"), timedelta(hours=12))

    def test_garbage_falls_back_to_one_day(self):
        self.assertEqual(collector_core.parse_since_to_timedelta("어쩌고"), timedelta(days=1))


class NormalizeItemTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 6, 20, 0, 0, tzinfo=timezone.utc)

    def test_missing_room_raises(self):
        with self.assertRaises(ValueError):
            collector_core.normalize_item({"text": "hi"}, self.now)

    def test_blank_text_raises(self):
        with self.assertRaises(ValueError):
            collector_core.normalize_item({"room": "ABC", "text": "  "}, self.now)

    def test_normalized_shape(self):
        rec = collector_core.normalize_item(
            {"room": " ABC ", "sender": " 홍길동 ", "text": "안녕", "ts": "오후 3:25"},
            self.now,
        )
        self.assertEqual(rec["room"], "ABC")
        self.assertEqual(rec["sender"], "홍길동")
        self.assertEqual(rec["text"], "안녕")
        self.assertEqual(rec["client_time"], "오후 3:25")
        self.assertEqual(rec["received_at"], "2026-06-20T00:00:00+00:00")


class SelectMessagesTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        self.items = [
            {"room": "ABC", "sender": "a", "text": "old", "client_time": "",
             "received_at": (self.now - timedelta(days=3)).isoformat()},
            {"room": "ABC", "sender": "b", "text": "recent", "client_time": "",
             "received_at": (self.now - timedelta(hours=2)).isoformat()},
            {"room": "OTHER", "sender": "c", "text": "otherroom", "client_time": "",
             "received_at": (self.now - timedelta(hours=1)).isoformat()},
        ]

    def test_since_window_filters_old(self):
        out = collector_core.select_messages(self.items, None, "1day", self.now)
        texts = [r["text"] for r in out]
        self.assertIn("recent", texts)
        self.assertIn("otherroom", texts)
        self.assertNotIn("old", texts)

    def test_room_filter(self):
        out = collector_core.select_messages(self.items, "ABC", "1day", self.now)
        self.assertEqual([r["text"] for r in out], ["recent"])

    def test_sorted_oldest_first(self):
        out = collector_core.select_messages(self.items, None, "7day", self.now)
        received = [r["received_at"] for r in out]
        self.assertEqual(received, sorted(received))


class ExpiredKeysTests(unittest.TestCase):
    def test_returns_keys_older_than_retention(self):
        now = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        items = [
            ("k_old", {"received_at": (now - timedelta(days=20)).isoformat()}),
            ("k_new", {"received_at": (now - timedelta(days=1)).isoformat()}),
        ]
        self.assertEqual(collector_core.expired_keys(items, now, retention_days=14), ["k_old"])


if __name__ == "__main__":
    unittest.main()
