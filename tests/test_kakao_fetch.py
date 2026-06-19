from pathlib import Path
import importlib.util
import io
import json
import unittest
from contextlib import redirect_stdout
from unittest import mock


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


class MainGuardTests(unittest.TestCase):
    def test_missing_env_prints_ok_false_json_and_returns_1(self):
        buf = io.StringIO()
        with mock.patch.dict(kakao_fetch.os.environ, {}, clear=True), \
                mock.patch.object(kakao_fetch.sys, "argv", ["kakao_fetch.py"]), \
                redirect_stdout(buf):
            rc = kakao_fetch.main()
        self.assertEqual(rc, 1)
        payload = json.loads(buf.getvalue())
        self.assertFalse(payload["ok"])
        self.assertIn("not set", payload["error"])


if __name__ == "__main__":
    unittest.main()
