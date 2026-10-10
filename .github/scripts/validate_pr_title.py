import os
import re
import sys


ALLOWED_TYPES = (
    "feat", "fix", "docs", "refactor", "test", "chore",
    "build", "ci", "perf", "style", "revert",
)

TITLE_PATTERN = re.compile(
    f"(?:{'|'.join(ALLOWED_TYPES)})"
    r"(?:\((?P<scope>[^)]+)\))?!?: (?P<description>.*)"
)


def is_valid_title(title: str) -> bool:
    match = TITLE_PATTERN.fullmatch(title)
    if match is None:
        return False

    scope = match.group("scope")
    return (
        (scope is None or bool(scope.strip()))
        and bool(match.group("description").strip())
    )


def main():
    if not is_valid_title(os.environ.get("PR_TITLE", "")):
        print(
            "Invalid pull request title. Expected: <type>[(scope)][!]: <description>.\n"
            f"Allowed types: {', '.join(ALLOWED_TYPES)}.\n"
            "Example: feat(training): add training state machine",
            file=sys.stderr,
        )
        return 1

    print("Pull request title follows Conventional Commits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
