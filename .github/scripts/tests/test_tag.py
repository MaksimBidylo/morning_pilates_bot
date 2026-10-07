import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tag


class VersionTests(unittest.TestCase):
    def test_first_release_starts_at_zero_one_zero(self):
        self.assertEqual("v0.1.0", tag.next_tag(None, ["feat: first feature"]))

    def test_fix_and_chore_increment_patch(self):
        self.assertEqual("v0.1.10", tag.next_tag("v0.1.9", ["fix: repair", "chore: infrastructure"]))

    def test_feature_wins_over_patch_and_resets_patch(self):
        self.assertEqual("v0.3.0", tag.next_tag("v0.2.8", ["fix: repair", "feat(bot): new command"]))

    def test_breaking_header_increments_major(self):
        self.assertEqual("v1.0.0", tag.next_tag("v0.9.2", ["feat: new feature", "refactor(db)!: replace schema"]))

    def test_breaking_footer_increments_major(self):
        self.assertEqual("v2.0.0", tag.next_tag("v1.2.3", ["fix: replace API\n\nBREAKING CHANGE: clients must migrate"]))


class GitTagTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous_directory = Path.cwd()
        root = Path(self.directory.name)
        self.remote = root / "remote.git"
        subprocess.run(["git", "init", "--bare", "--initial-branch=main", str(self.remote)], check=True, capture_output=True)
        repository = root / "repository"
        repository.mkdir()
        os.chdir(repository)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")
        self.git("remote", "add", "origin", str(self.remote))

    def tearDown(self):
        os.chdir(self.previous_directory)
        self.directory.cleanup()

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], check=True, text=True, capture_output=True).stdout.strip()

    def commit(self, message):
        self.git("commit", "--allow-empty", "--message", message)
        self.git("push", "origin", "main")
        return self.git("rev-parse", "HEAD")

    def test_release_pushes_exact_commit_and_rerun_keeps_same_tag(self):
        commit = self.commit("chore: bootstrap")
        self.assertEqual(("v0.1.0", True), tag.create_tag(commit)[:2])
        self.assertEqual(("v0.1.0", False), tag.create_tag(commit)[:2])
        self.assertEqual(commit, self.git("--git-dir", str(self.remote), "rev-parse", "refs/tags/v0.1.0^{commit}"))

    def test_next_release_uses_changes_since_previous_release(self):
        tag.create_tag(self.commit("chore: bootstrap"))
        tag.create_tag(self.commit("feat: new command"))
        self.assertEqual(("v0.2.1", True), tag.create_tag(self.commit("fix: reply target"))[:2])

    def test_late_ci_for_older_commit_does_not_create_newer_version_for_old_code(self):
        old_commit = self.commit("chore: initial")
        new_commit = self.commit("feat: feature")
        tag.create_tag(new_commit)
        self.assertEqual(("v0.1.0", False), tag.create_tag(old_commit)[:2])
        self.assertEqual("v0.1.0", self.git("tag", "--list"))

    def test_commit_outside_main_is_rejected_without_creating_tags(self):
        self.commit("chore: initial")
        self.git("checkout", "-b", "feature")
        self.git("commit", "--allow-empty", "--message", "feat: unmerged feature")
        with self.assertRaisesRegex(ValueError, "belong to main"):
            tag.create_tag(self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("tag", "--list"))


if __name__ == "__main__":
    unittest.main()
