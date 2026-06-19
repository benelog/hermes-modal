from pathlib import Path
import importlib.util
import os
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "prepare_runtime.py"
spec = importlib.util.spec_from_file_location("prepare_runtime", MODULE_PATH)
prepare_runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_runtime)


class PrepareRuntimeTests(unittest.TestCase):
    def test_load_repo_specs_reads_qmd_git_repo_manifest(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manifest = Path(tmpdir) / "qmd_repos.yml"
            manifest.write_text(
                """
repos:
  - name: diary
    repo_url: git@github.com:benelog/diary.git
    branch: master
    collection_path: content
  - name: blog
    repo_url: git@github.com:benelog/blog.git
    branch: master
    collection_path: src/content
    pattern: "**/*.md"
""".strip(),
                encoding="utf-8",
            )

            repos = prepare_runtime.load_repo_specs(manifest)

        self.assertEqual(
            repos,
            [
                {
                    "name": "diary",
                    "repo_url": "git@github.com:benelog/diary.git",
                    "branch": "master",
                    "collection_path": "content",
                    "pattern": "**/*.{md,adoc}",
                },
                {
                    "name": "blog",
                    "repo_url": "git@github.com:benelog/blog.git",
                    "branch": "master",
                    "collection_path": "src/content",
                    "pattern": "**/*.md",
                },
            ],
        )

    def test_repo_url_uses_public_https_without_token(self):
        old = os.environ.pop("GITHUB_TOKEN", None)
        try:
            self.assertEqual(
                prepare_runtime.repo_url("git@github.com:benelog/diary.git"),
                "https://github.com/benelog/diary.git",
            )
        finally:
            if old is not None:
                os.environ["GITHUB_TOKEN"] = old

    def test_repo_url_converts_github_ssh_url_when_token_is_available(self):
        old = os.environ.get("GITHUB_TOKEN")
        os.environ["GITHUB_TOKEN"] = "secret-token"
        try:
            self.assertEqual(
                prepare_runtime.repo_url("git@github.com:benelog/diary.git"),
                "https://x-access-token:secret-token@github.com/benelog/diary.git",
            )
        finally:
            if old is None:
                os.environ.pop("GITHUB_TOKEN", None)
            else:
                os.environ["GITHUB_TOKEN"] = old

    def test_sanitize_command_redacts_github_token(self):
        self.assertEqual(
            prepare_runtime.sanitize_command(
                [
                    "git",
                    "clone",
                    "https://x-access-token:secret-token@github.com/benelog/diary.git",
                    "/tmp/diary",
                ]
            ),
            "git clone https://x-access-token:***@github.com/benelog/diary.git /tmp/diary",
        )

    def test_write_env_file_includes_kakao_keys(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            saved_home = prepare_runtime.HERMES_HOME
            saved_url = os.environ.get("KAKAO_MESSAGES_URL")
            saved_token = os.environ.get("KAKAO_COLLECTOR_TOKEN")
            prepare_runtime.HERMES_HOME = Path(tmpdir)
            os.environ["KAKAO_MESSAGES_URL"] = "https://x--kakao-messages.modal.run"
            os.environ["KAKAO_COLLECTOR_TOKEN"] = "tok-123"
            try:
                prepare_runtime.write_env_file()
                content = (Path(tmpdir) / ".env").read_text(encoding="utf-8")
            finally:
                prepare_runtime.HERMES_HOME = saved_home
                if saved_url is None:
                    os.environ.pop("KAKAO_MESSAGES_URL", None)
                else:
                    os.environ["KAKAO_MESSAGES_URL"] = saved_url
                if saved_token is None:
                    os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)
                else:
                    os.environ["KAKAO_COLLECTOR_TOKEN"] = saved_token
            self.assertIn('KAKAO_MESSAGES_URL="https://x--kakao-messages.modal.run"', content)
            self.assertIn('KAKAO_COLLECTOR_TOKEN="tok-123"', content)


if __name__ == "__main__":
    unittest.main()
