import base64
from contextlib import redirect_stdout
import io
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
import setup_compose


class ComposeSetupTests(unittest.TestCase):
    keys = [base64.b64encode(bytes(range(start, start + 32))).decode() for start in (0, 32)]

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name)
        self.dotenv = self.project / ".env"
        self.local_config = self.project / "src/main/resources/application-local.conf"
        self.output = io.StringIO()
        self.generator = self.enterContext(patch.object(setup_compose, "generate_key", side_effect=self.keys))
        self.commands = self.enterContext(patch.object(setup_compose.subprocess, "run"))
        self.prompt = self.enterContext(patch.object(setup_compose.getpass, "getpass", return_value="123:test-token"))

    def setup(self, environment=None, start=False):
        with redirect_stdout(self.output):
            setup_compose.setup(self.project, environment or {}, start=start)

    def test_first_run_saves_independent_keys_and_hidden_token_without_printing_secrets(self):
        self.setup()
        self.assertEqual(dict(zip(setup_compose.KEY_NAMES, self.keys)),
                         setup_compose.dotenv_keys(self.dotenv.read_text()))
        self.assertIn('telegram.botToken = "123:test-token"', self.local_config.read_text())
        self.assertEqual(2, self.generator.call_count)
        for path in (self.dotenv, self.local_config):
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        for secret in self.keys + ["123:test-token"]:
            self.assertNotIn(secret, self.output.getvalue())
        self.assertEqual(["config", "--quiet"], self.commands.call_args.args[0][-2:])

    def test_repeat_run_preserves_keys_and_existing_configuration(self):
        original_env = "# local configuration\nAPP_PORT=8081\n" + "\n".join(
            f'{name}="{value}"' for name, value in zip(setup_compose.KEY_NAMES, self.keys)) + "\n"
        self.dotenv.write_text(original_env)
        self.local_config.parent.mkdir(parents=True)
        self.local_config.write_text('telegram.botToken = "existing-token"\ndb.host = "postgres"\n')
        original_config = self.local_config.read_text()
        self.setup()
        self.setup()
        self.generator.assert_not_called()
        self.prompt.assert_not_called()
        self.assertEqual(original_env, self.dotenv.read_text())
        self.assertEqual(original_config, self.local_config.read_text())

    def test_missing_key_is_added_without_replacing_existing_key_or_other_settings(self):
        self.dotenv.write_text(f"APP_PORT=8081\n{setup_compose.KEY_NAMES[0]}={self.keys[0]}\n")
        self.generator.side_effect = [self.keys[1]]
        self.setup()
        self.assertEqual(dict(zip(setup_compose.KEY_NAMES, self.keys)),
                         setup_compose.dotenv_keys(self.dotenv.read_text()))
        self.assertIn("APP_PORT=8081\n", self.dotenv.read_text())
        self.generator.assert_called_once()

    def test_existing_environment_keys_are_saved_and_token_needs_no_prompt(self):
        self.setup(dict(zip(setup_compose.KEY_NAMES, self.keys)) | {"TELEGRAM_BOT_TOKEN": "123:env-token"})
        self.generator.assert_not_called()
        self.prompt.assert_not_called()
        self.assertIn('"123:env-token"', self.local_config.read_text())

    def test_invalid_or_equal_existing_keys_are_rejected_without_file_changes_or_secret_leaks(self):
        for bad_key in ("", "not:base64!", base64.b64encode(bytes(31)).decode(),
                        base64.b64encode(bytes(33)).decode(), self.keys[0]):
            with self.subTest(key=bad_key):
                original = f"{setup_compose.KEY_NAMES[0]}={self.keys[0]}\n{setup_compose.KEY_NAMES[1]}={bad_key}\n"
                self.dotenv.write_text(original)
                with self.assertRaises(ValueError) as failure:
                    self.setup()
                self.assertEqual(original, self.dotenv.read_text())
                for secret in [self.keys[0], bad_key]:
                    if secret:
                        self.assertNotIn(secret, str(failure.exception))
        self.generator.assert_not_called()
        self.commands.assert_not_called()

    def test_conflicting_shell_key_is_rejected_without_changing_saved_key(self):
        original = f"{setup_compose.KEY_NAMES[0]}={self.keys[0]}\n"
        self.dotenv.write_text(original)
        with self.assertRaisesRegex(ValueError, "различаются"):
            self.setup({setup_compose.KEY_NAMES[0]: self.keys[1]})
        self.assertEqual(original, self.dotenv.read_text())
        self.generator.assert_not_called()

    def test_up_runs_only_after_configuration_check(self):
        self.setup(start=True)
        self.assertEqual(["config", "--quiet"], self.commands.call_args_list[0].args[0][-2:])
        self.assertEqual(["up", "--build", "-d"], self.commands.call_args_list[1].args[0][-3:])

    def test_failed_configuration_check_prevents_container_start(self):
        import subprocess
        self.commands.side_effect = subprocess.CalledProcessError(1, ["docker", "compose"])
        with self.assertRaises(subprocess.CalledProcessError):
            self.setup(start=True)
        self.commands.assert_called_once()

    def test_empty_token_leaves_secret_files_uncreated(self):
        self.prompt.return_value = ""
        with self.assertRaisesRegex(ValueError, "Токен"):
            self.setup()
        self.assertFalse(self.dotenv.exists())
        self.assertFalse(self.local_config.exists())


if __name__ == "__main__":
    unittest.main()
