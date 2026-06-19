from pathlib import Path
import importlib.util
import os
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "create_modal_secret.py"
spec = importlib.util.spec_from_file_location("create_modal_secret", MODULE_PATH)
cms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cms)


class KakaoTokenTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        # ~/.hermes/.env 등 로컬 파일 영향 제거
        cms.LOCAL_ENV = Path(self._tmp.name) / ".env"
        cms.LOCAL_AUTH = Path(self._tmp.name) / "auth.json"
        self._saved = os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)
        # build_secret_values는 TELEGRAM_BOT_TOKEN을 요구하지 않지만 main()만 요구
        os.environ["TELEGRAM_BOT_TOKEN"] = "test-bot-token"

    def tearDown(self):
        self._tmp.cleanup()
        os.environ.pop("TELEGRAM_BOT_TOKEN", None)
        if self._saved is not None:
            os.environ["KAKAO_COLLECTOR_TOKEN"] = self._saved

    def test_token_is_generated_when_absent(self):
        values = cms.build_secret_values(None)
        self.assertIn("KAKAO_COLLECTOR_TOKEN", values)
        self.assertGreaterEqual(len(values["KAKAO_COLLECTOR_TOKEN"]), 20)

    def test_token_from_env_is_used(self):
        os.environ["KAKAO_COLLECTOR_TOKEN"] = "fixed-token-value-123"
        try:
            values = cms.build_secret_values(None)
            self.assertEqual(values["KAKAO_COLLECTOR_TOKEN"], "fixed-token-value-123")
        finally:
            os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)


if __name__ == "__main__":
    unittest.main()
