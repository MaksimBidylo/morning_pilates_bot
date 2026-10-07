import json
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

PENDING_STATUSES = {"created", "queued", "build_in_progress", "pre_deploy_in_progress", "update_in_progress"}
FAILED_STATUSES = {"build_failed", "pre_deploy_failed", "update_failed", "canceled", "deactivated"}
RETRYABLE_HTTP_STATUSES = {404, 429, 500, 502, 503, 504}
DEPLOY_TIMEOUT_SECONDS = 10 * 60
POLL_INTERVAL_SECONDS = 15


def deploy_url(hook, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full commit SHA")
    parts = urlsplit(hook)
    if parts.scheme != "https" or parts.netloc != "api.render.com" or not re.fullmatch(r"/deploy/srv-[a-z0-9]+", parts.path):
        raise ValueError("Configure a valid Render deploy hook in RENDER_DEPLOY_HOOK_URL")
    parameters = [(key, value) for key, value in parse_qsl(parts.query, keep_blank_values=True) if key != "ref"]
    if not any(key == "key" and value for key, value in parameters):
        raise ValueError("Render deploy hook is missing its key")
    parameters.append(("ref", commit))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(parameters), ""))


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def trigger_deploy(hook, commit):
    request = Request(deploy_url(hook, commit), data=b"", method="POST")
    with build_opener(NoRedirect()).open(request, timeout=30) as response:
        if response.status not in (200, 202):
            raise RuntimeError(f"Unexpected Render hook response: HTTP {response.status}")
        if response.status == 202:
            return 202, None
        body = json.load(response)
        deploy_id = body.get("deploy", {}).get("id")
        if not isinstance(deploy_id, str) or not re.fullmatch(r"dep-[a-z0-9]+", deploy_id):
            raise RuntimeError("Render started the deploy but did not return a valid deploy ID; check Render before retrying")
        return 200, deploy_id


class RenderApi:
    def __init__(self, service_id, api_key):
        if not re.fullmatch(r"srv-[a-z0-9]+", service_id):
            raise ValueError("Invalid Render service ID")
        if not api_key.strip():
            raise ValueError("Configure RENDER_API_KEY in GitHub Secrets to wait for the deploy result")
        self.service_id = service_id
        self.api_key = api_key
        self.opener = build_opener(NoRedirect())

    @property
    def dashboard_url(self):
        return f"https://dashboard.render.com/web/{self.service_id}"

    def get(self, path):
        request = Request(
            f"https://api.render.com/v1/services/{self.service_id}/deploys{path}",
            headers={"Authorization": f"Bearer {self.api_key}", "Accept": "application/json"},
        )
        with self.opener.open(request, timeout=30) as response:
            if response.status != 200:
                raise RuntimeError(f"Unexpected Render API response: HTTP {response.status}")
            return json.load(response)

    def deploy(self, deploy_id):
        if not re.fullmatch(r"dep-[a-z0-9]+", deploy_id):
            raise ValueError("Invalid Render deploy ID")
        return self.get(f"/{deploy_id}")

    def recent_deploys(self, created_after):
        deploys = []
        parameters = {"limit": 100, "createdAfter": created_after}
        cursors = set()
        while True:
            page = self.get("?" + urlencode(parameters))
            deploys.extend(item["deploy"] for item in page)
            if len(page) < 100:
                return deploys
            cursor = page[-1].get("cursor")
            if not cursor or cursor in cursors:
                raise RuntimeError("Could not read Render deploy history completely")
            cursors.add(cursor)
            parameters["cursor"] = cursor


def wait_for_deploy(api, commit, deploy_id, previous_ids, created_after, deadline):
    last_status = None
    while time.monotonic() < deadline:
        try:
            if deploy_id is None:
                matches = [
                    deploy for deploy in api.recent_deploys(created_after)
                    if deploy["id"] not in previous_ids
                    and (deploy.get("commit") or {}).get("id") == commit
                    and deploy.get("trigger") == "deploy_hook"
                ]
                if len(matches) > 1:
                    raise RuntimeError("Several new hook deploys match this commit; cannot identify the requested deploy")
                if matches:
                    deploy_id = matches[0]["id"]
                    print(f"Tracking Render deploy {deploy_id}: {api.dashboard_url}", flush=True)

            if deploy_id is not None:
                deploy = api.deploy(deploy_id)
                if deploy.get("id") != deploy_id:
                    raise RuntimeError("Render returned a different deploy ID")
                actual_commit = (deploy.get("commit") or {}).get("id")
                if actual_commit and actual_commit != commit:
                    raise RuntimeError("Render deploy does not match the selected commit")
                status = deploy.get("status")
                if status != last_status:
                    print(f"Render deploy {deploy_id}: {status}", flush=True)
                    last_status = status
                if status == "live":
                    if actual_commit != commit:
                        raise RuntimeError("Cannot confirm the commit of the live Render deploy")
                    return deploy_id
                if status in FAILED_STATUSES:
                    raise RuntimeError(f"Render deploy {deploy_id} finished with status {status}")
                if status not in PENDING_STATUSES:
                    raise RuntimeError("Render returned an unknown deploy status")
        except HTTPError as error:
            if error.code not in RETRYABLE_HTTP_STATUSES:
                raise
            print(f"Render status is temporarily unavailable (HTTP {error.code}); retrying", flush=True)
        except (URLError, TimeoutError, OSError):
            print("Render status is temporarily unavailable; retrying", flush=True)

        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(POLL_INTERVAL_SECONDS, remaining))
    raise RuntimeError("Timed out waiting for Render to make the selected commit live; the deploy may still be running")


def report(message):
    print(message, flush=True)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a") as output:
            output.write(message + "\n")


def main():
    commit = os.environ["DEPLOY_SHA"]
    hook = os.environ.get("RENDER_DEPLOY_HOOK_URL", "")
    service_id = urlsplit(deploy_url(hook, commit)).path.rsplit("/", 1)[1]
    api = RenderApi(service_id, os.environ.get("RENDER_API_KEY", ""))
    created_after = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
    previous_ids = {deploy["id"] for deploy in api.recent_deploys(created_after)}
    deadline = time.monotonic() + DEPLOY_TIMEOUT_SECONDS
    status, deploy_id = trigger_deploy(hook, commit)
    message = "Render started the deploy" if status == 200 else "Render queued the deploy"
    print(f"{message} for {commit}; waiting for live. {api.dashboard_url}", flush=True)
    try:
        deploy_id = wait_for_deploy(api, commit, deploy_id, previous_ids, created_after, deadline)
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, RuntimeError, KeyError):
        report(f"Deploy of `{commit}` was not confirmed as live. [Render service]({api.dashboard_url}).")
        raise
    report(f"Render deploy `{deploy_id}` is **live**, commit `{commit}`. [Render service]({api.dashboard_url}).")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        print(f"Render request failed: HTTP {error.code}", file=sys.stderr)
        sys.exit(1)
    except (URLError, TimeoutError, OSError):
        print("Could not reach Render; check its deploy history before retrying.", file=sys.stderr)
        sys.exit(1)
    except (ValueError, RuntimeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
