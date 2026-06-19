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
        # ~/.hermes/.env 등 로컬 파일 영향 제거. 모듈 전역을 임시 경로로 가리키되
        # 원래 값을 보관해 tearDown에서 복원한다(다른 테스트로의 누수 방지).
        self._saved_local_env = cms.LOCAL_ENV
        self._saved_local_auth = cms.LOCAL_AUTH
        cms.LOCAL_ENV = Path(self._tmp.name) / ".env"
        cms.LOCAL_AUTH = Path(self._tmp.name) / "auth.json"
        # build_secret_values는 TELEGRAM_BOT_TOKEN을 요구하지 않으므로 설정하지 않는다.
        self._saved_token = os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)

    def tearDown(self):
        self._tmp.cleanup()
        cms.LOCAL_ENV = self._saved_local_env
        cms.LOCAL_AUTH = self._saved_local_auth
        if self._saved_token is not None:
            os.environ["KAKAO_COLLECTOR_TOKEN"] = self._saved_token

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
