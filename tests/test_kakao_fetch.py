from pathlib import Path
import importlib.util
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "cron" / "kakao_fetch.py"
spec = importlib.util.spec_from_file_location("kakao_fetch", MODULE_PATH)
kakao_fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kakao_fetch)


class BuildUrlTests(unittest.TestCase):
    def test_includes_token_and_since(self):
        url = kakao_fetch.build_url("https://x--kakao-messages.modal.run", "tok", "1day", "")
        self.assertIn("token=tok", url)
        self.assertIn("since=1day", url)
        self.assertNotIn("room=", url)

    def test_includes_room_when_given(self):
        url = kakao_fetch.build_url("https://x--kakao-messages.modal.run/", "tok", "3day", "아카라카북클럽")
        self.assertIn("room=", url)
        self.assertIn("since=3day", url)
        # base의 뒤쪽 슬래시는 중복되지 않아야 함
        self.assertNotIn(".run//?", url)


if __name__ == "__main__":
    unittest.main()
