import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


def deploy_url(hook, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full commit SHA")
    parts = urlsplit(hook)
    if parts.scheme != "https" or parts.netloc != "api.render.com" or not parts.path.startswith("/deploy/"):
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
            raise RuntimeError(f"Unexpected Render response: HTTP {response.status}")
        return response.status


def main():
    commit = os.environ["DEPLOY_SHA"]
    status = trigger_deploy(os.environ.get("RENDER_DEPLOY_HOOK_URL", ""), commit)
    message = "Render started the deploy" if status == 200 else "Render queued the deploy"
    print(f"{message} for commit {commit}. Check its outcome in Render.")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a") as output:
            output.write(f"{message} for `{commit}`. This confirms acceptance, not a completed deploy.\n")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        print(f"Render rejected the deploy: HTTP {error.code}", file=sys.stderr)
        sys.exit(1)
    except (URLError, TimeoutError, OSError):
        print("Could not reach Render; check its deploy history before retrying.", file=sys.stderr)
        sys.exit(1)
    except (ValueError, RuntimeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
