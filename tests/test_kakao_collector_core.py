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

    # ── sent_time(발신 시각) 병합 ─────────────────────────────────

    def test_timeless_existing_upgraded_by_timed_incoming(self):
        e = self._rec("동일한 메시지")
        incoming = self._rec("동일한 메시지")
        incoming["sent_time"] = "14:20"
        plan = collector_core.plan_ingest([("E", e)], incoming, "K")
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["key"], "E")  # in place — received_at(순서) 보존
        self.assertEqual(plan["rec"]["sent_time"], "14:20")

    def test_conflicting_times_keep_earliest(self):
        # 시각 오귀속은 늦은 값으로만 튄다(아래쪽 라벨 오결합) → 이른 관측이 남아야 한다.
        e = self._rec("동일한 메시지")
        e["sent_time"] = "09:05"
        late, early = self._rec("동일한 메시지"), self._rec("동일한 메시지")
        late["sent_time"], early["sent_time"] = "14:20", "08:59"
        self.assertEqual(collector_core.plan_ingest([("E", e)], late, "K")["action"], "skip")
        plan = collector_core.plan_ingest([("E", e)], early, "K")
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["rec"]["sent_time"], "08:59")

    def test_truncated_incoming_still_fills_date_and_time(self):
        # 잘린 재수집이라도 실린 날짜/시각은 기존(더 완전한) 행에 채운다 — 본문은 기존 유지.
        e = self._rec("오픈은 10시더라고 정말로 그래서 줄섰다", ct="")
        incoming = self._rec("오픈은 10시더라고 정말로", ct="2026-06-24")
        incoming["sent_time"] = "10:02"
        plan = collector_core.plan_ingest([("E", e)], incoming, "K")
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["rec"]["text"], "오픈은 10시더라고 정말로 그래서 줄섰다")
        self.assertEqual(plan["rec"]["client_time"], "2026-06-24")
        self.assertEqual(plan["rec"]["sent_time"], "10:02")

    def test_extension_update_fills_date_and_time(self):
        e = self._rec("오픈은 10시더라고 정말로", ct="")
        incoming = self._rec("오픈은 10시더라고 정말로 그래서 줄섰다", ct="2026-06-24")
        incoming["sent_time"] = "10:02"
        plan = collector_core.plan_ingest([("E", e)], incoming, "K")
        self.assertEqual(plan["action"], "update")
        self.assertEqual(plan["rec"]["text"], "오픈은 10시더라고 정말로 그래서 줄섰다")
        self.assertEqual(plan["rec"]["client_time"], "2026-06-24")
        self.assertEqual(plan["rec"]["sent_time"], "10:02")


class SentTimeTests(unittest.TestCase):
    def test_normalize_accepts_hhmm_only(self):
        self.assertEqual(collector_core.normalize_sent_time("14:20"), "14:20")
        self.assertEqual(collector_core.normalize_sent_time(" 09:05 "), "09:05")
        for bad in ("", None, "9:05", "24:00", "14:60", "오후 3:01", "2026-06-24"):
            self.assertEqual(collector_core.normalize_sent_time(bad), "")

    def test_earliest_time(self):
        self.assertEqual(collector_core.earliest_time("09:05", "14:20"), "09:05")
        self.assertEqual(collector_core.earliest_time("", "14:20"), "14:20")
        self.assertEqual(collector_core.earliest_time("14:20", ""), "14:20")
        self.assertEqual(collector_core.earliest_time("", ""), "")

    def test_normalize_item_includes_sent_time(self):
        now = datetime(2026, 6, 20, 0, 0, tzinfo=timezone.utc)
        rec = collector_core.normalize_item(
            {"room": "ABC", "text": "안녕", "ts": "2026-06-19", "sent_time": "21:40"}, now
        )
        self.assertEqual(rec["sent_time"], "21:40")
        rec = collector_core.normalize_item({"room": "ABC", "text": "안녕"}, now)
        self.assertEqual(rec["sent_time"], "")


class EffectiveSentAtTests(unittest.TestCase):
    def test_date_and_time_win(self):
        rec = {"client_time": "2026-06-24", "sent_time": "14:20",
               "received_at": "2026-06-30T00:00:00+00:00"}
        dt = collector_core.effective_sent_at(rec)
        self.assertEqual((dt.year, dt.month, dt.day, dt.hour, dt.minute), (2026, 6, 24, 14, 20))
        self.assertEqual(dt.utcoffset(), timedelta(hours=9))  # KST

    def test_date_only_uses_received_clock_on_that_date(self):
        # 날짜만 알면 그 날짜(KST) + 수신 시:분 — 같은 백필 배치 안에서는 수신 순서가
        # 곧 대화 순서라 상대 순서가 보존된다.
        rec = {"client_time": "2026-06-24", "sent_time": "",
               "received_at": "2026-06-30T05:00:00+00:00"}  # KST 14:00
        dt = collector_core.effective_sent_at(rec)
        self.assertEqual((dt.year, dt.month, dt.day, dt.hour), (2026, 6, 24, 14))

    def test_no_date_falls_back_to_received(self):
        rec = {"client_time": "", "sent_time": "", "received_at": "2026-06-30T05:00:00+00:00"}
        self.assertEqual(
            collector_core.effective_sent_at(rec),
            datetime(2026, 6, 30, 5, 0, tzinfo=timezone.utc),
        )

    def test_backfilled_week_sorts_in_true_order(self):
        # 일주일치를 오늘 한꺼번에 백필해도(수신은 전부 오늘) 발신 날짜/시각 순으로 정렬된다.
        now = datetime(2026, 7, 13, 3, 0, tzinfo=timezone.utc)
        base = "2026-07-13T02:{:02d}:00+00:00"
        items = [
            {"room": "ABC", "text": "d3", "client_time": "2026-07-12", "sent_time": "09:00",
             "received_at": base.format(0)},
            {"room": "ABC", "text": "d1", "client_time": "2026-07-10", "sent_time": "22:10",
             "received_at": base.format(1)},
            {"room": "ABC", "text": "d2", "client_time": "2026-07-11", "sent_time": "",
             "received_at": base.format(2)},
        ]
        out = collector_core.select_messages(items, "ABC", "7day", now)
        self.assertEqual([r["text"] for r in out], ["d1", "d2", "d3"])

    def test_since_window_uses_sent_date_not_received(self):
        # 오늘 백필된 '8일 전 발신' 메시지는 7day 창에 안 들어와야 한다.
        now = datetime(2026, 7, 13, 3, 0, tzinfo=timezone.utc)
        items = [
            {"room": "ABC", "text": "too-old", "client_time": "2026-07-04", "sent_time": "12:00",
             "received_at": "2026-07-13T02:00:00+00:00"},
            {"room": "ABC", "text": "in-window", "client_time": "2026-07-10", "sent_time": "12:00",
             "received_at": "2026-07-13T02:00:00+00:00"},
        ]
        out = collector_core.select_messages(items, "ABC", "7day", now)
        self.assertEqual([r["text"] for r in out], ["in-window"])


if __name__ == "__main__":
    unittest.main()
