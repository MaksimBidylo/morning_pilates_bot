import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy_render


class DeployTests(unittest.TestCase):
    commit = "a" * 40
    hook = "https://api.render.com/deploy/srv-test?key=test-key&ref=old"

    def test_hook_uses_selected_commit_and_preserves_key(self):
        url = deploy_render.deploy_url(self.hook, self.commit)
        parameters = parse_qs(urlsplit(url).query)
        self.assertEqual([self.commit], parameters["ref"])
        self.assertEqual(["test-key"], parameters["key"])

    def test_missing_secret_and_invalid_commit_are_rejected(self):
        with self.assertRaises(ValueError):
            deploy_render.deploy_url("", self.commit)
        with self.assertRaises(ValueError):
            deploy_render.deploy_url(self.hook, "main")

    def test_requests_are_sent_only_to_render_over_https(self):
        for hook in ("http://api.render.com/deploy/srv-test?key=test", "https://other.invalid/deploy/srv-test?key=test"):
            with self.assertRaises(ValueError):
                deploy_render.deploy_url(hook, self.commit)

    def test_deploy_posts_exact_commit_and_accepts_started_or_queued_response(self):
        for status in (200, 202):
            opener = MagicMock()
            opener.open.return_value.__enter__.return_value.status = status
            with patch.object(deploy_render, "build_opener", return_value=opener):
                self.assertEqual(status, deploy_render.trigger_deploy(self.hook, self.commit))
            request = opener.open.call_args.args[0]
            self.assertEqual("POST", request.get_method())
            self.assertEqual([self.commit], parse_qs(urlsplit(request.full_url).query)["ref"])


if __name__ == "__main__":
    unittest.main()
