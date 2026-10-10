import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_pr_title


class PRTitleValidationTests(unittest.TestCase):
    def test_accepts_all_allowed_types_without_scope(self):
        # Given
        types = (
            "feat", "fix", "docs", "refactor", "test", "chore",
            "build", "ci", "perf", "style", "revert",
        )

        for commit_type in types:
            with self.subTest(commit_type=commit_type):
                title = f"{commit_type}: describe the change"

                # When
                result = validate_pr_title.is_valid_title(title)

                # Then
                self.assertIs(result, True)

    def test_accepts_nonempty_scope(self):
        for title in (
            "feat(training): add training state machine",
            "refactor(flow): simplify handler selection",
            "fix(parser-v2): handle invalid user input",
        ):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), True)

    def test_accepts_breaking_change_with_and_without_scope(self):
        for title in (
            "feat!: change public API",
            "refactor(flow)!: change FlowHandler contract",
        ):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), True)

    def test_preserves_freedom_of_description(self):
        for title in (
            "fix: исправить обработку даты",
            "docs: update README! (details): version 2",
            "docs: explain `!`, quotes and $(command)",
            "ci:  validate pull request titles  ",
        ):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), True)

    def test_rejects_unknown_type(self):
        for title in ("feature: add training", "unknown: describe the change"):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)

    def test_rejects_type_with_incorrect_case(self):
        for title in ("Feat: add training", "FIX: handle invalid user input"):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)

    def test_rejects_missing_colon(self):
        for title in (
            "fix(scope) add validation",
            "feat add training",
            "feat(training)! change public API",
        ):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)

    def test_rejects_missing_required_space_after_colon(self):
        for title in ("feat:add training", "feat:\tadd training"):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)

    def test_rejects_empty_or_whitespace_only_description(self):
        for title in ("feat:", "feat: ", "feat:    ", "feat: \t", "fix(flow)!: "):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)

    def test_rejects_empty_scope(self):
        self.assertIs(validate_pr_title.is_valid_title("feat(): add training"), False)

    def test_rejects_whitespace_only_scope(self):
        # Given
        for scope in ("  ", "\t", " \t "):
            with self.subTest(scope=scope):
                title = f"feat({scope}): add training"

                # When
                result = validate_pr_title.is_valid_title(title)

                # Then
                self.assertIs(result, False)

    def test_rejects_misplaced_or_repeated_breaking_change_marker(self):
        for title in (
            "feat!(training): add training",
            "feat!!: change public API",
            "feat(training)!!: change public API",
        ):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)

    def test_rejects_arbitrary_text_or_partial_match(self):
        for title in (
            "",
            "Add new feature",
            "prefix feat: add training",
            " feat: add training",
            "feat(training) extra: add training",
        ):
            with self.subTest(title=title):
                self.assertIs(validate_pr_title.is_valid_title(title), False)


class PRTitleCliTests(unittest.TestCase):
    def run_cli(self, title=None):
        environment = os.environ.copy()
        environment.pop("PR_TITLE", None)
        if title is not None:
            environment["PR_TITLE"] = title

        script = Path(__file__).resolve().parents[1] / "validate_pr_title.py"
        return subprocess.run(
            [sys.executable, "-B", str(script)],
            env=environment,
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_accepts_valid_title(self):
        # Given / When
        result = self.run_cli("ci: validate pull request titles")

        # Then
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)

    def test_rejects_invalid_title_with_format_and_allowed_types(self):
        # Given / When
        result = self.run_cli("Add new feature")

        # Then
        self.assertEqual(1, result.returncode)
        self.assertIn("<type>[(scope)][!]: <description>", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertIn(
            "feat, fix, docs, refactor, test, chore, build, ci, perf, style, revert",
            result.stderr,
        )

    def test_reports_missing_title_without_traceback(self):
        # Given / When
        result = self.run_cli()

        # Then
        self.assertEqual(1, result.returncode)
        self.assertIn("<type>[(scope)][!]: <description>", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
