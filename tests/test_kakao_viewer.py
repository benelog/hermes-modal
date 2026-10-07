from pathlib import Path
import sys
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from kakao.viewer import day_stats, render_viewer  # noqa: E402


class RenderViewerTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 7, 12, 0, tzinfo=timezone.utc)
        self.items = [
            {"room": "ABC", "sender": "가", "text": "<script>alert(1)</script>", "client_time": "2026-10-06",
             "sent_time": "21:05", "received_at": (self.now - timedelta(hours=5)).isoformat()},
            {"room": "ABC", "sender": "나", "text": "날짜 없음", "client_time": "", "sent_time": "",
             "received_at": (self.now - timedelta(hours=1)).isoformat()},
            {"room": "모임", "sender": "다", "text": "다른 방", "client_time": "2026-10-07", "sent_time": "",
             "received_at": (self.now - timedelta(hours=2)).isoformat()},
            {"room": "ABC", "sender": "라", "text": "오래됨", "client_time": "2026-09-20", "sent_time": "",
             "received_at": (self.now - timedelta(days=17)).isoformat()},
        ]

    def test_escapes_message_text_and_token(self):
        page = render_viewer(self.items, "", "3day", self.now, 'tok"><b>')
        self.assertNotIn("<script>alert(1)</script>", page)
        self.assertIn("&lt;script&gt;", page)
        self.assertNotIn('tok"><b>', page)

    def test_room_filter_and_window(self):
        page = render_viewer(self.items, "ABC", "3day", self.now, "t")
        self.assertIn("날짜 없음", page)
        self.assertNotIn("다른 방", page)
        self.assertNotIn("오래됨", page)
        self.assertIn("2건", page)

    def test_unknown_room_and_since_fall_back(self):
        page = render_viewer(self.items, "없는방", "bogus", self.now, "t")
        self.assertIn("다른 방", page)
        self.assertIn('value="3day" selected', page)

    def test_estimated_time_marked(self):
        page = render_viewer(self.items, "ABC", "3day", self.now, "t")
        self.assertIn(">21:05<", page)
        self.assertIn(">~20:00<", page)  # 11:00 UTC 수신 → 20:00 KST 추정
        self.assertIn("날짜?", page)

    def test_day_stats_counts_newest_first(self):
        stats = day_stats(self.items[:3])
        self.assertEqual([s["date"] for s in stats], ["2026-10-07 (수)", "2026-10-06 (화)"])
        self.assertEqual((stats[0]["total"], stats[0]["dated"], stats[0]["timed"]), (2, 1, 0))
        self.assertEqual((stats[1]["total"], stats[1]["dated"], stats[1]["timed"]), (1, 1, 1))


if __name__ == "__main__":
    unittest.main()
