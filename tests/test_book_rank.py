from pathlib import Path
import importlib.util
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "book_rank.py"
spec = importlib.util.spec_from_file_location("book_rank", MODULE_PATH)
book_rank = importlib.util.module_from_spec(spec)
sys.modules["book_rank"] = book_rank  # dataclass가 모듈을 조회하므로 등록 필요
spec.loader.exec_module(book_rank)

YES24_PRODUCT = """
<script>
$.get("/Product/addModules/BestSellerRank_Book/199971666/?categoryNumber=001001003032004&FreePrice=N", function (data) {
</script>
"""

YES24_MODULE = """
<span class="gd_best gd_best_tp02">
    <dl>
        <dt>베스트</dt>
        <dd>
            <a href="javascript:void(0)" onclick="openUrl('/product/category/bestseller?CategoryNumber=001001003&amp;SumGb=02','Pcode','003_008')">IT 모바일 84위</a>
                <em class="divi">|</em>
            IT 모바일 top100 1주
        </dd>
    </dl>
</span>
"""

ALADIN_PRODUCT = """
<div id="wa_product_top1_wa_Top_Ranking_pnlRanking" class="info_list">
    <div class="Ere_fs15 Ere_ht18"><a href="/shop/common/wbest.aspx?BranchType=1&BestType=Bestseller&cid=351">컴퓨터/모바일 주간 85위</a><span class="Ere_PR10"></span>|<div>Sales Point : <strong>500</strong></div></div>
</div>
"""

KYOBO_PRODUCT = """
<div class="flex items-center gap-1"><a draggable="false" class="flex items-center gap-1" href="https://store.kyobobook.co.kr/bestseller/online/weekly/domestic/33"><div class="flex"><svg width="24"><path d="M1 2"/></svg><span>주간베스트</span><span>컴퓨터/IT</span> <span>117</span><span>위</span></div></a></div>
"""


def fake_get(pages):
    def get(url, referer=None):
        for key, body in pages.items():
            if key in url:
                return body
        raise AssertionError(f"unexpected url {url}")
    return get


class ParserTests(unittest.TestCase):
    def test_yes24_reads_module_then_rank(self):
        get = fake_get({"addModules": YES24_MODULE, "goods/199971666": YES24_PRODUCT})
        r = book_rank.fetch_yes24("https://www.yes24.com/product/goods/199971666", get)
        self.assertEqual((r.category, r.rank, r.note), ("IT 모바일", 84, "IT 모바일 top100 1주"))

    def test_yes24_empty_module_means_no_rank(self):
        r = book_rank.parse_yes24_rank("")
        self.assertIsNone(r.rank)
        self.assertIsNone(r.error)

    def test_aladin_splits_period_from_category(self):
        r = book_rank.parse_aladin_rank(ALADIN_PRODUCT)
        self.assertEqual((r.category, r.rank, r.note), ("컴퓨터/모바일", 85, "주간"))

    def test_kyobo_reads_bestseller_link(self):
        r = book_rank.parse_kyobo_rank(KYOBO_PRODUCT)
        self.assertEqual((r.category, r.rank, r.note), ("컴퓨터/IT", 117, "주간"))

    def test_kyobo_blank_response_is_reported_as_error(self):
        r = book_rank.fetch_kyobo("https://product.kyobobook.co.kr/detail/x", lambda url, referer=None: "")
        self.assertIn("빈 응답", r.error)

    def test_collect_all_isolates_failures(self):
        def get(url, referer=None):
            if "aladin" in url:
                raise TimeoutError("timed out")
            if "kyobo" in url:
                return KYOBO_PRODUCT
            return YES24_MODULE if "addModules" in url else YES24_PRODUCT
        results = book_rank.collect_all(get)
        self.assertEqual(results["yes24"].rank, 84)
        self.assertEqual(results["kyobo"].rank, 117)
        self.assertIn("TimeoutError", results["aladin"].error)


def rank(category, n, **kw):
    return book_rank.StoreRank("x", category, n, **kw)


class HistoryTests(unittest.TestCase):
    def build(self):
        recs = []
        recs = book_rank.upsert_record(recs, "2026-11-17", {"yes24": rank("IT 모바일", 90), "aladin": rank("컴퓨터/모바일", 85)}, "t")
        recs = book_rank.upsert_record(recs, "2026-11-18", {"yes24": rank("IT 모바일", 84), "aladin": rank("프로그래밍 언어", 12)}, "t")
        return recs

    def test_upsert_replaces_same_date_and_sorts(self):
        recs = self.build()
        recs = book_rank.upsert_record(recs, "2026-11-16", {"yes24": rank("IT 모바일", 99)}, "t")
        recs = book_rank.upsert_record(recs, "2026-11-18", {"yes24": rank("IT 모바일", 80)}, "t")
        self.assertEqual([r["date"] for r in recs], ["2026-11-16", "2026-11-17", "2026-11-18"])
        self.assertEqual(recs[-1]["stores"]["yes24"]["rank"], 80)

    def test_delta_and_category_change(self):
        latest = book_rank.annotate(self.build())[-1]["stores"]
        self.assertEqual(latest["yes24"]["delta"], 6)
        self.assertEqual(latest["aladin"]["category_changed_from"], "컴퓨터/모바일")
        self.assertNotIn("delta", latest["aladin"])

    def test_failed_day_is_skipped_for_comparison(self):
        recs = self.build()
        recs = book_rank.upsert_record(recs, "2026-11-19", {"yes24": book_rank.StoreRank("yes24", error="x")}, "t")
        recs = book_rank.upsert_record(recs, "2026-11-20", {"yes24": rank("IT 모바일", 80)}, "t")
        self.assertEqual(book_rank.annotate(recs)[-1]["stores"]["yes24"]["delta"], 4)

    def test_save_and_load_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sub" / "history.json"
            book_rank.save_history(path, self.build())
            self.assertEqual(book_rank.load_history(path), self.build())

    def test_telegram_message_mentions_change_and_link(self):
        msg = book_rank.format_telegram_message(self.build(), "2026-11-18", "https://example/page")
        self.assertIn("YES24: IT 모바일 84위 (▲6)", msg)
        self.assertIn("교보문고: 수집 실패", msg)
        self.assertIn("집계 부문 변경", msg)
        self.assertIn("알라딘: 컴퓨터/모바일 → 프로그래밍 언어", msg)
        self.assertIn("https://example/page", msg)

    def test_html_table_has_rows_newest_first(self):
        page = book_rank.render_html(self.build())
        self.assertLess(page.index("2026-11-18"), page.index("2026-11-17"))
        self.assertIn("부문 변경: 컴퓨터/모바일 → 프로그래밍 언어", page)
        self.assertIn('class="changed"', page)

    def test_html_empty_history(self):
        self.assertIn("아직 수집된 기록이 없습니다", book_rank.render_html([]))


class ManualInputTests(unittest.TestCase):
    def test_rank_only_uses_previous_category(self):
        date, r = book_rank.parse_manual_input("117", "컴퓨터/IT")
        self.assertIsNone(date)
        self.assertEqual((r.category, r.rank, r.note, r.manual), ("컴퓨터/IT", 117, "주간", True))

    def test_category_date_and_suffix(self):
        date, r = book_rank.parse_manual_input("2026-11-18 컴퓨터/IT 117위", None)
        self.assertEqual((date, r.category, r.rank), ("2026-11-18", "컴퓨터/IT", 117))
        _, r = book_rank.parse_manual_input("IT 일반 3 위", "컴퓨터/IT")
        self.assertEqual((r.category, r.rank), ("IT 일반", 3))

    def test_no_rank(self):
        _, r = book_rank.parse_manual_input("없음", "컴퓨터/IT")
        self.assertIsNone(r.rank)
        self.assertIsNone(r.error)

    def test_invalid_or_missing_category(self):
        with self.assertRaises(ValueError):
            book_rank.parse_manual_input("abc", "컴퓨터/IT")
        with self.assertRaises(ValueError):
            book_rank.parse_manual_input("117", None)

    def test_auto_failure_keeps_manual_value(self):
        recs = book_rank.upsert_record([], "2026-11-18", {
            "yes24": rank("IT 모바일", 84),
            "kyobo": book_rank.StoreRank("kyobo", error="빈 응답"),
        }, "t1")
        _, manual = book_rank.parse_manual_input("컴퓨터/IT 117", None)
        recs = book_rank.upsert_record(recs, "2026-11-18", {"kyobo": manual}, "t1")
        self.assertEqual(recs[0]["stores"]["yes24"]["rank"], 84)  # 다른 서점은 유지
        recs = book_rank.upsert_record(recs, "2026-11-18", {
            "yes24": rank("IT 모바일", 80),
            "kyobo": book_rank.StoreRank("kyobo", error="빈 응답"),
        }, "t2")
        stores = recs[0]["stores"]
        self.assertEqual(stores["yes24"]["rank"], 80)
        self.assertEqual((stores["kyobo"]["rank"], stores["kyobo"]["manual"]), (117, True))
        msg = book_rank.format_telegram_message(recs, "2026-11-18")
        self.assertIn("교보문고: 컴퓨터/IT 주간 117위 (수동)", msg)

    def test_failure_message_has_kyobo_hint(self):
        recs = book_rank.upsert_record([], "2026-11-18", {"kyobo": book_rank.StoreRank("kyobo", error="x")}, "t")
        self.assertIn("/kyobo 117", book_rank.format_telegram_message(recs, "2026-11-18"))

    def test_last_category_skips_errors(self):
        recs = book_rank.upsert_record([], "2026-11-17", {"kyobo": rank("컴퓨터/IT", 120)}, "t")
        recs = book_rank.upsert_record(recs, "2026-11-18", {"kyobo": book_rank.StoreRank("kyobo", error="x")}, "t")
        self.assertEqual(book_rank.last_category(recs, "kyobo"), "컴퓨터/IT")


class PluginTests(unittest.TestCase):
    def test_registers_kyobo_command_and_shows_usage(self):
        import asyncio
        path = MODULE_PATH.parent / "plugins" / "book-rank" / "__init__.py"
        pspec = importlib.util.spec_from_file_location("book_rank_plugin", path)
        plugin = importlib.util.module_from_spec(pspec)
        pspec.loader.exec_module(plugin)
        registered = {}

        class Ctx:
            def register_command(self, name, handler, description="", args_hint=""):
                registered[name] = handler

        plugin.register(Ctx())
        self.assertIn("kyobo", registered)
        self.assertIn("/kyobo 117", asyncio.run(registered["kyobo"]("  ")))


if __name__ == "__main__":
    unittest.main()
