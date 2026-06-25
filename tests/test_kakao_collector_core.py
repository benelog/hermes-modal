from pathlib import Path
import importlib.util
import unittest
from datetime import datetime, timedelta, timezone


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "kakao" / "collector_core.py"
spec = importlib.util.spec_from_file_location("collector_core", MODULE_PATH)
collector_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector_core)


class MessageKeyTests(unittest.TestCase):
    def test_same_message_same_key(self):
        a = collector_core.message_key("아카라카북클럽", "안녕", "2026-06-24")
        b = collector_core.message_key("아카라카북클럽", "안녕", "2026-06-24")
        self.assertEqual(a, b)

    def test_different_text_different_key(self):
        a = collector_core.message_key("아카라카북클럽", "안녕", "2026-06-24")
        b = collector_core.message_key("아카라카북클럽", "잘가", "2026-06-24")
        self.assertNotEqual(a, b)

    def test_different_day_different_key(self):
        a = collector_core.message_key("아카라카북클럽", "안녕", "2026-06-24")
        b = collector_core.message_key("아카라카북클럽", "안녕", "2026-06-25")
        self.assertNotEqual(a, b)

    def test_key_is_prefixed_with_room(self):
        key = collector_core.message_key("아카라카북클럽", "안녕", "2026-06-24")
        self.assertTrue(key.startswith("아카라카북클럽|"))

    def test_field_boundary_is_unambiguous(self):
        # Fields are joined with a delimiter so shifting a character across a
        # field boundary still produces a different key (guards the dedupe id).
        a = collector_core.message_key("R", "ab", "x")
        b = collector_core.message_key("R", "a", "bx")
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


class ExtractSinceTests(unittest.TestCase):
    def test_default_when_no_period(self):
        self.assertEqual(collector_core.extract_since("요약해줘"), "1day")
        self.assertEqual(collector_core.extract_since(""), "1day")
        self.assertEqual(collector_core.extract_since(None), "1day")

    def test_explicit_days(self):
        self.assertEqual(collector_core.extract_since("3일치 요약해줘"), "3day")
        self.assertEqual(collector_core.extract_since("최근 5일 대화 요약"), "5day")
        self.assertEqual(collector_core.extract_since("summarize last 2 days"), "2day")
        self.assertEqual(collector_core.extract_since("2d 요약"), "2day")

    def test_explicit_hours(self):
        self.assertEqual(collector_core.extract_since("최근 12시간 요약해줘"), "12hour")
        self.assertEqual(collector_core.extract_since("6h 요약"), "6hour")

    def test_keyword_fallbacks(self):
        self.assertEqual(collector_core.extract_since("오늘 대화 요약해줘"), "1day")
        self.assertEqual(collector_core.extract_since("어제 요약"), "2day")
        self.assertEqual(collector_core.extract_since("이번주 요약 부탁"), "7day")
        self.assertEqual(collector_core.extract_since("일주일치 요약"), "7day")

    def test_hours_take_priority_over_day_keyword(self):
        # An explicit "12시간" must win even if a day-keyword is also present.
        self.assertEqual(collector_core.extract_since("오늘 최근 12시간만 요약"), "12hour")

    def test_result_is_parseable(self):
        # Whatever extract_since returns must be understood by parse_since_to_timedelta.
        for cmd in ["요약", "3일치 요약", "12시간 요약", "이번주 요약", "어제 요약"]:
            td = collector_core.parse_since_to_timedelta(collector_core.extract_since(cmd))
            self.assertGreater(td, timedelta(0))


class ExtractUrlsTests(unittest.TestCase):
    def _msgs(self, *texts):
        return [{"text": t} for t in texts]

    def test_no_urls_returns_empty(self):
        self.assertEqual(collector_core.extract_urls(self._msgs("그냥 대화", "책 추천")), [])

    def test_extracts_http_and_https(self):
        out = collector_core.extract_urls(
            self._msgs("이거 봐 https://example.com/a", "http://foo.io/b 도")
        )
        self.assertEqual(out, ["https://example.com/a", "http://foo.io/b"])

    def test_extracts_www_without_scheme(self):
        self.assertEqual(
            collector_core.extract_urls(self._msgs("www.naver.com 참고")),
            ["www.naver.com"],
        )

    def test_dedupes_preserving_first_seen_order(self):
        out = collector_core.extract_urls(
            self._msgs("https://b.com", "https://a.com", "다시 https://b.com")
        )
        self.assertEqual(out, ["https://b.com", "https://a.com"])

    def test_strips_trailing_chat_punctuation(self):
        out = collector_core.extract_urls(
            self._msgs("여기 https://example.com/page. 끝", "링크(https://kakao.com)")
        )
        self.assertEqual(out, ["https://example.com/page", "https://kakao.com"])

    def test_keeps_balanced_parens_in_url(self):
        out = collector_core.extract_urls(
            self._msgs("https://en.wikipedia.org/wiki/Dune_(novel)")
        )
        self.assertEqual(out, ["https://en.wikipedia.org/wiki/Dune_(novel)"])

    def test_multiple_urls_in_one_message(self):
        out = collector_core.extract_urls(
            self._msgs("https://a.com 그리고 https://b.com")
        )
        self.assertEqual(out, ["https://a.com", "https://b.com"])

    def test_ignores_missing_text(self):
        self.assertEqual(collector_core.extract_urls([{"sender": "a"}, {"text": None}]), [])


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

    def test_strips_reply_prefix_from_text(self):
        rec = collector_core.normalize_item(
            {"room": "ABC", "sender": "추정하", "text": "답장 메시지 파란책 내용 좋네요."},
            self.now,
        )
        self.assertEqual(rec["text"], "파란책 내용 좋네요.")

    def test_text_blank_after_stripping_prefix_raises(self):
        with self.assertRaises(ValueError):
            collector_core.normalize_item({"room": "ABC", "text": "답장 메시지   "}, self.now)


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


class CleanTextTests(unittest.TestCase):
    def test_strips_korean_reply_prefix(self):
        self.assertEqual(
            collector_core.clean_text("답장 메시지 파란책 내용 좋네요."), "파란책 내용 좋네요."
        )

    def test_trims_whitespace_around_and_after_prefix(self):
        self.assertEqual(collector_core.clean_text("  답장 메시지   안녕 "), "안녕")

    def test_leaves_plain_text(self):
        self.assertEqual(collector_core.clean_text("그냥 메시지"), "그냥 메시지")

    def test_blank_and_none(self):
        self.assertEqual(collector_core.clean_text(""), "")
        self.assertEqual(collector_core.clean_text(None), "")


class ExtendsTests(unittest.TestCase):
    def test_longer_continuation_extends_shorter(self):
        self.assertTrue(
            collector_core.extends("오픈은 10시더라고 정말로", "오픈은 10시더라고 정말로 그래서 줄섰다")
        )

    def test_too_short_prefix_is_not_confident(self):
        self.assertFalse(collector_core.extends("안녕", "안녕 반가워요 오랜만입니다"))

    def test_strips_trailing_ellipsis_before_compare(self):
        self.assertTrue(
            collector_core.extends("충분히 긴 시작 문장인데…", "충분히 긴 시작 문장인데 계속 이어집니다")
        )

    def test_non_prefix_is_not_extension(self):
        self.assertFalse(collector_core.extends("완전히 다른 시작 문장", "전혀 관계 없는 다른 문장"))

    def test_equal_is_not_extension(self):
        self.assertFalse(collector_core.extends("같은 길이의 문장입니다요", "같은 길이의 문장입니다요"))

    def test_first_arg_must_be_the_shorter(self):
        self.assertFalse(
            collector_core.extends("충분히 긴 시작 문장인데 계속 이어집니다", "충분히 긴 시작 문장인데")
        )


class PlanIngestTests(unittest.TestCase):
    def _rec(self, text, room="ABC", sender="s", ct="2026-06-24",
             ra="2026-06-24T00:00:00+00:00"):
        return {"room": room, "sender": sender, "text": text,
                "client_time": ct, "received_at": ra}

    def test_empty_store_stores(self):
        plan = collector_core.plan_ingest([], self._rec("새 메시지"), "K")
        self.assertEqual(plan["action"], "store")
        self.assertEqual(plan["key"], "K")

    def test_exact_same_text_and_day_skips(self):
        e = self._rec("동일한 메시지")
        plan = collector_core.plan_ingest([("E", e)], self._rec("동일한 메시지"), "K")
        self.assertEqual(plan["action"], "skip")
        self.assertEqual(plan["key"], "E")

    def test_same_text_different_day_stores(self):
        e = self._rec("동일한 메시지", ct="2026-06-24")
        plan = collector_core.plan_ingest(
            [("E", e)], self._rec("동일한 메시지", ct="2026-06-25"), "K"
        )
        self.assertEqual(plan["action"], "store")

    def test_extension_updates_existing_in_place(self):
        e = self._rec("오픈은 10시더라고 정말로", ra="2026-06-24T01:00:00+00:00")
        plan = collector_core.plan_ingest(
            [("E", e)], self._rec("오픈은 10시더라고 정말로 그래서 줄섰다"), "K"
        )
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["key"], "E")  # keep existing slot => received_at preserved
        self.assertEqual(plan["rec"]["text"], "오픈은 10시더라고 정말로 그래서 줄섰다")
        self.assertEqual(plan["rec"]["received_at"], "2026-06-24T01:00:00+00:00")

    def test_truncated_incoming_skips_when_fuller_exists(self):
        e = self._rec("오픈은 10시더라고 정말로 그래서 줄섰다")
        plan = collector_core.plan_ingest([("E", e)], self._rec("오픈은 10시더라고 정말로"), "K")
        self.assertEqual(plan["action"], "skip")
        self.assertEqual(plan["key"], "E")

    def test_no_merge_across_rooms(self):
        e = self._rec("오픈은 10시더라고 정말로", room="OTHER")
        plan = collector_core.plan_ingest(
            [("E", e)], self._rec("오픈은 10시더라고 정말로 그래서 줄섰다", room="ABC"), "K"
        )
        self.assertEqual(plan["action"], "store")

    def test_dateless_existing_upgraded_by_dated_incoming(self):
        # empty→date transition: a stored dateless copy is upgraded in place, not duplicated.
        e = self._rec("좋은 아침입니다 여러분", ct="", ra="2026-06-24T01:00:00+00:00")
        plan = collector_core.plan_ingest([("E", e)], self._rec("좋은 아침입니다 여러분", ct="2026-06-26"), "K")
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["key"], "E")
        self.assertEqual(plan["rec"]["client_time"], "2026-06-26")
        self.assertEqual(plan["rec"]["received_at"], "2026-06-24T01:00:00+00:00")

    def test_dated_existing_not_duplicated_by_dateless_incoming(self):
        e = self._rec("좋은 아침입니다 여러분", ct="2026-06-26")
        plan = collector_core.plan_ingest([("E", e)], self._rec("좋은 아침입니다 여러분", ct=""), "K")
        self.assertEqual(plan["action"], "skip")

    def test_same_text_two_known_days_kept_separate(self):
        e = self._rec("좋은 아침입니다 여러분", ct="2026-06-25")
        plan = collector_core.plan_ingest([("E", e)], self._rec("좋은 아침입니다 여러분", ct="2026-06-26"), "K")
        self.assertEqual(plan["action"], "store")

    def test_update_fills_missing_sender(self):
        e = self._rec("오픈은 10시더라고 정말로", sender="")
        plan = collector_core.plan_ingest(
            [("E", e)], self._rec("오픈은 10시더라고 정말로 그래서 줄섰다", sender="지민경"), "K"
        )
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["rec"]["sender"], "지민경")


if __name__ == "__main__":
    unittest.main()
