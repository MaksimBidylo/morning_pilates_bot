import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy_render


class FakeClock:
    def __init__(self):
        self.now = 0

    def sleep(self, seconds):
        self.now += seconds


class DeployTests(unittest.TestCase):
    commit = "a" * 40
    hook = "https://api.render.com/deploy/srv-test?key=test-key&ref=old"

    def deploy(self, status, deploy_id="dep-new", commit=None, trigger="deploy_hook"):
        return {"id": deploy_id, "status": status, "commit": {"id": commit or self.commit}, "trigger": trigger}

    def wait(self, api, deploy_id="dep-new", previous_ids=None, timeout=60):
        clock = FakeClock()
        with patch.object(deploy_render.time, "monotonic", side_effect=lambda: clock.now), \
                patch.object(deploy_render.time, "sleep", side_effect=clock.sleep), \
                patch("builtins.print"):
            return deploy_render.wait_for_deploy(api, self.commit, deploy_id, previous_ids or set(), timeout)

    def test_hook_uses_selected_commit_and_preserves_key(self):
        parameters = parse_qs(urlsplit(deploy_render.deploy_url(self.hook, self.commit)).query)
        self.assertEqual([self.commit], parameters["ref"])
        self.assertEqual(["test-key"], parameters["key"])

    def test_missing_secret_invalid_commit_and_non_render_urls_are_rejected(self):
        for hook, commit in (("", self.commit), (self.hook, "main"),
                             ("http://api.render.com/deploy/srv-test?key=test", self.commit),
                             ("https://other.invalid/deploy/srv-test?key=test", self.commit),
                             ("https://api.render.com/deploy/srv-test/other?key=test", self.commit)):
            with self.subTest(hook=hook), self.assertRaises(ValueError):
                deploy_render.deploy_url(hook, commit)

    def test_hook_posts_exact_commit_and_returns_id_or_queue(self):
        for status, expected_id in ((200, "dep-new"), (202, None)):
            opener = MagicMock()
            response = opener.open.return_value.__enter__.return_value
            response.status = status
            response.read.return_value = json.dumps({"deploy": {"id": "dep-new"}}).encode()
            with patch.object(deploy_render, "build_opener", return_value=opener):
                self.assertEqual((status, expected_id), deploy_render.trigger_deploy(self.hook, self.commit))
            request = opener.open.call_args.args[0]
            self.assertEqual("POST", request.get_method())
            self.assertFalse(request.has_header("Authorization"))
            self.assertEqual([self.commit], parse_qs(urlsplit(request.full_url).query)["ref"])

    def test_hook_response_without_deploy_id_is_not_success(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.status = 200
        response.read.return_value = b'{}'
        with patch.object(deploy_render, "build_opener", return_value=opener), self.assertRaisesRegex(RuntimeError, "deploy ID"):
            deploy_render.trigger_deploy(self.hook, self.commit)

    def test_api_authenticates_only_fixed_render_endpoint(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.status = 200
        response.read.return_value = json.dumps(self.deploy("live")).encode()
        with patch.object(deploy_render, "build_opener", return_value=opener):
            api = deploy_render.RenderApi("srv-test", "test-api-key")
            self.assertEqual(self.deploy("live"), api.deploy("dep-new"))
        request = opener.open.call_args.args[0]
        self.assertEqual("https://api.render.com/v1/services/srv-test/deploys/dep-new", request.full_url)
        self.assertEqual("Bearer test-api-key", request.get_header("Authorization"))
        self.assertEqual("GET", request.get_method())

    def test_waits_through_build_and_update_until_selected_deploy_is_live(self):
        api = MagicMock()
        api.deploy.side_effect = [self.deploy(status) for status in ("build_in_progress", "pre_deploy_in_progress", "update_in_progress", "live")]
        self.assertEqual("dep-new", self.wait(api))
        self.assertEqual(4, api.deploy.call_count)
        api.list_deploys.assert_not_called()

    def test_failed_canceled_or_deactivated_deploy_fails_the_job(self):
        for status in ("build_failed", "pre_deploy_failed", "update_failed", "canceled", "deactivated"):
            api = MagicMock()
            api.deploy.return_value = self.deploy(status)
            with self.subTest(status=status), self.assertRaisesRegex(RuntimeError, status):
                self.wait(api)

    def test_live_deploy_of_another_commit_or_id_is_not_success(self):
        for deploy in (self.deploy("live", commit="b" * 40), self.deploy("live", deploy_id="dep-other"),
                       {"id": "dep-new", "status": "live"}):
            api = MagicMock()
            api.deploy.return_value = deploy
            with self.subTest(deploy=deploy), self.assertRaises(RuntimeError):
                self.wait(api)

    def test_unknown_status_is_not_success(self):
        api = MagicMock()
        api.deploy.return_value = self.deploy("unknown")
        with self.assertRaisesRegex(RuntimeError, "unknown"):
            self.wait(api)

    def test_wait_times_out_instead_of_reporting_success(self):
        api = MagicMock()
        api.deploy.return_value = self.deploy("queued")
        with self.assertRaisesRegex(RuntimeError, "Timed out"):
            self.wait(api, timeout=30)
        self.assertEqual(2, api.deploy.call_count)

    def test_queued_request_ignores_old_live_deploy_of_same_sha_and_other_commits(self):
        api = MagicMock()
        old = self.deploy("live", deploy_id="dep-old")
        other_commit = self.deploy("live", deploy_id="dep-other", commit="b" * 40)
        manual = self.deploy("live", deploy_id="dep-manual", trigger="manual")
        api.list_deploys.side_effect = [[old, other_commit, manual], [old, self.deploy("build_in_progress")]]
        api.deploy.return_value = self.deploy("live")
        self.assertEqual("dep-new", self.wait(api, deploy_id=None, previous_ids={"dep-old"}))
        api.deploy.assert_called_once_with("dep-new")

    def test_queued_request_without_new_deploy_times_out(self):
        api = MagicMock()
        api.list_deploys.return_value = [self.deploy("live", deploy_id="dep-old")]
        with self.assertRaisesRegex(RuntimeError, "Timed out"):
            self.wait(api, deploy_id=None, previous_ids={"dep-old"}, timeout=30)
        api.deploy.assert_not_called()

    def test_ambiguous_queued_deploys_are_not_reported_as_success(self):
        api = MagicMock()
        api.list_deploys.return_value = [self.deploy("live"), self.deploy("live", deploy_id="dep-other")]
        with self.assertRaisesRegex(RuntimeError, "Several"):
            self.wait(api, deploy_id=None)

    def test_temporary_api_failures_are_retried_without_triggering_another_deploy(self):
        for error in (URLError("offline"), HTTPError("https://api.render.com", 429, "rate limited", {}, io.BytesIO()),
                      HTTPError("https://api.render.com", 503, "unavailable", {}, io.BytesIO())):
            api = MagicMock()
            api.deploy.side_effect = [error, self.deploy("live")]
            with self.subTest(error=type(error)), patch.object(deploy_render, "trigger_deploy") as trigger:
                self.assertEqual("dep-new", self.wait(api))
                trigger.assert_not_called()

    def test_api_authorization_failure_is_not_retried(self):
        api = MagicMock()
        api.deploy.side_effect = HTTPError("https://api.render.com", 401, "unauthorized", {}, io.BytesIO())
        self.addCleanup(api.deploy.side_effect.close)
        with self.assertRaises(HTTPError):
            self.wait(api)
        api.deploy.assert_called_once()

    def test_missing_api_key_prevents_triggering_deploy(self):
        with patch.dict(deploy_render.os.environ, {"DEPLOY_SHA": self.commit, "RENDER_DEPLOY_HOOK_URL": self.hook}, clear=True), \
                patch.object(deploy_render, "trigger_deploy") as trigger, self.assertRaisesRegex(ValueError, "RENDER_API_KEY"):
            deploy_render.main()
        trigger.assert_not_called()

    def test_invalid_api_access_is_detected_before_triggering_deploy(self):
        error = HTTPError("https://api.render.com", 403, "forbidden", {}, io.BytesIO())
        self.addCleanup(error.close)
        with patch.dict(deploy_render.os.environ, {"DEPLOY_SHA": self.commit, "RENDER_DEPLOY_HOOK_URL": self.hook, "RENDER_API_KEY": "test-key"}), \
                patch.object(deploy_render.RenderApi, "list_deploys", side_effect=error), \
                patch.object(deploy_render, "trigger_deploy") as trigger, self.assertRaises(HTTPError):
            deploy_render.main()
        trigger.assert_not_called()

    def test_main_reports_success_only_after_live_and_includes_deploy_commit_and_link(self):
        api = MagicMock()
        api.list_deploys.return_value = [self.deploy("live", deploy_id="dep-old")]
        api.dashboard_url = "https://dashboard.render.com/web/srv-test"
        with patch.dict(deploy_render.os.environ, {"DEPLOY_SHA": self.commit, "RENDER_DEPLOY_HOOK_URL": self.hook, "RENDER_API_KEY": "test-key"}), \
                patch.object(deploy_render, "RenderApi", return_value=api), \
                patch.object(deploy_render, "trigger_deploy", return_value=(200, "dep-new")), \
                patch.object(deploy_render, "wait_for_deploy", return_value="dep-new") as wait, \
                patch.object(deploy_render, "report") as report, patch("builtins.print"):
            deploy_render.main()
        self.assertEqual({"dep-old"}, wait.call_args.args[3])
        message = report.call_args.args[0]
        for expected in ("**live**", "dep-new", self.commit, api.dashboard_url):
            self.assertIn(expected, message)

    def test_main_reports_failure_when_deploy_fails(self):
        api = MagicMock()
        api.list_deploys.return_value = []
        api.dashboard_url = "https://dashboard.render.com/web/srv-test"
        with patch.dict(deploy_render.os.environ, {"DEPLOY_SHA": self.commit, "RENDER_DEPLOY_HOOK_URL": self.hook, "RENDER_API_KEY": "test-key"}), \
                patch.object(deploy_render, "RenderApi", return_value=api), \
                patch.object(deploy_render, "trigger_deploy", return_value=(200, "dep-new")), \
                patch.object(deploy_render, "wait_for_deploy", side_effect=RuntimeError("build_failed")), \
                patch.object(deploy_render, "report") as report, patch("builtins.print"), self.assertRaisesRegex(RuntimeError, "build_failed"):
            deploy_render.main()
        self.assertIn("not confirmed", report.call_args.args[0])
        self.assertNotIn("**live**", report.call_args.args[0])


    def test_history_request_has_no_date_filter_and_reads_all_pages(self):
        api = deploy_render.RenderApi("srv-test", "test-key")
        page = [{"deploy": self.deploy("live", deploy_id=f"dep-old{index}"), "cursor": f"cursor{index}"} for index in range(100)]
        with patch.object(api, "get", side_effect=[page, [{"deploy": self.deploy("live"), "cursor": "last"}]]) as get:
            deploys = api.list_deploys()
        self.assertEqual(101, len(deploys))
        for call in get.call_args_list:
            parameters = parse_qs(urlsplit(call.args[0]).query)
            self.assertEqual(["100"], parameters["limit"])
            self.assertNotIn("createdAfter", parameters)
        self.assertEqual(["cursor99"], parse_qs(urlsplit(get.call_args_list[1].args[0]).query)["cursor"])

    def test_api_and_hook_http_errors_identify_the_failed_request(self):
        for is_hook, expected in ((False, "Render API (list deploys)"), (True, "Render deploy hook")):
            error = HTTPError("https://api.render.com", 400, "Bad request", {}, io.BytesIO(b'{"message":"invalid parameter"}'))
            opener = MagicMock()
            opener.open.side_effect = error
            with self.subTest(is_hook=is_hook), patch.object(deploy_render, "build_opener", return_value=opener):
                with self.assertRaises(HTTPError) as raised:
                    if is_hook:
                        deploy_render.trigger_deploy(self.hook, self.commit)
                    else:
                        deploy_render.RenderApi("srv-test", "test-key").list_deploys()
                message = deploy_render.http_error_message(raised.exception)
            self.assertIn(expected, message)
            self.assertIn("HTTP 400: invalid parameter", message)

    def test_error_message_redacts_secrets_urls_and_encoded_credentials(self):
        hook = "https://api.render.com/deploy/srv-test?key=hook-secret%2B"
        detail = "bad parameter; hook-secret+; hook-secret%2B; api-secret+; api-secret%2B; " + hook + "\nBearer another-secret"
        error = HTTPError(hook, 400, "bad request", {}, io.BytesIO(json.dumps({"message": detail}).encode()))
        with patch.dict(deploy_render.os.environ, {"RENDER_DEPLOY_HOOK_URL": hook, "RENDER_API_KEY": "api-secret+"}):
            message = deploy_render.http_error_message(error)
        self.assertIn("bad parameter", message)
        for secret in ("hook-secret", "api-secret", "another-secret", "https://", "\n"):
            self.assertNotIn(secret, message)

    def test_non_json_error_response_is_not_dumped_to_logs(self):
        error = HTTPError("https://api.render.com", 400, "bad request", {}, io.BytesIO(b'<html>secret-token</html>'))
        message = deploy_render.http_error_message(error)
        self.assertIn("HTTP 400", message)
        self.assertNotIn("secret-token", message)


if __name__ == "__main__":
    unittest.main()
