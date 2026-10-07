import os
import re
import subprocess
import sys
from pathlib import Path

TAG_PATTERN = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def next_tag(previous_tag, messages):
    if previous_tag is None:
        return "v0.1.0"
    major, minor, patch = map(int, TAG_PATTERN.fullmatch(previous_tag).groups())
    bump = "patch"
    for message in messages:
        subject = message.splitlines()[0] if message else ""
        if re.match(r"^[a-z][a-z0-9-]*(?:\([^\r\n)]+\))?!:\s", subject) or re.search(
                r"^BREAKING[ -]CHANGE:\s", message, re.MULTILINE
        ):
            bump = "major"
            break
        if re.match(r"^feat(?:\([^\r\n)]+\))?:\s", subject):
            bump = "minor"
    if bump == "major":
        return f"v{major + 1}.0.0"
    if bump == "minor":
        return f"v{major}.{minor + 1}.0"
    return f"v{major}.{minor}.{patch + 1}"


def git(*args):
    return subprocess.run(["git", *args], check=True, text=True, capture_output=True).stdout.strip()


def is_ancestor(ancestor, descendant):
    result = subprocess.run(["git", "merge-base", "--is-ancestor", ancestor, descendant])
    if result.returncode not in (0, 1):
        raise RuntimeError("Cannot determine commit ancestry")
    return result.returncode == 0


def create_tag(commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full commit SHA")
    git("fetch", "origin", "main", "--tags")
    if not is_ancestor(commit, "origin/main"):
        raise ValueError("Release commit must belong to main")

    tags = [tag for tag in git("tag", "--list").splitlines() if TAG_PATTERN.fullmatch(tag)]
    tags.sort(key=lambda tag: tuple(map(int, TAG_PATTERN.fullmatch(tag).groups())))
    for tag in reversed(tags):
        if git("rev-parse", f"refs/tags/{tag}^{{commit}}") == commit:
            return tag, False, "Commit already has a release tag"

    previous_tag = tags[-1] if tags else None
    if previous_tag:
        previous_commit = git("rev-parse", f"refs/tags/{previous_tag}^{{commit}}")
        if is_ancestor(commit, previous_commit):
            return previous_tag, False, "A newer main commit has already been released"
        if not is_ancestor(previous_commit, commit):
            raise ValueError("Latest release is not an ancestor of this commit")

    commit_range = f"{previous_tag}..{commit}" if previous_tag else commit
    messages = git("log", "--format=%B%x00", commit_range).split("\x00")
    tag = next_tag(previous_tag, [message.strip() for message in messages if message.strip()])
    git("tag", "--annotate", tag, commit, "--message", f"Release {tag}")
    git("push", "origin", f"refs/tags/{tag}")
    return tag, True, "Version tag created"


def main():
    tag, created, message = create_tag(sys.argv[1])
    print(f"{message}: {tag}")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a") as output:
            output.write(f"{message}: **{tag}**.\n")
    if output_path := os.environ.get("GITHUB_OUTPUT"):
        with Path(output_path).open("a") as output:
            output.write(f"tag={tag}\ncreated={str(created).lower()}\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Tag creation failed: {error}", file=sys.stderr)
        sys.exit(1)
