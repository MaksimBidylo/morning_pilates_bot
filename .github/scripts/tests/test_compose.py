import base64
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class ComposeKeyConfigurationTests(unittest.TestCase):
    repository = Path(__file__).resolve().parents[3]
    key_names = ("TELEGRAM_ID_ENCRYPTION_KEY", "TELEGRAM_ID_LOOKUP_KEY")
    test_keys = dict(zip(key_names, (
        base64.b64encode(bytes(range(32))).decode("ascii"),
        base64.b64encode(bytes(range(32, 64))).decode("ascii"),
    )))

    @classmethod
    def setUpClass(cls):
        if not shutil.which("docker"):
            raise unittest.SkipTest("Docker CLI is required for Compose configuration checks")
        result = subprocess.run(["docker", "compose", "version"], capture_output=True, text=True)
        if result.returncode:
            raise unittest.SkipTest("Docker Compose is unavailable")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        shutil.copyfile(self.repository / "compose.yaml", self.directory / "compose.yaml")
        self.environment = {
            name: value for name, value in os.environ.items()
            if name not in self.key_names and not name.startswith("COMPOSE_")
        }

    def compose_config(self, keys):
        return subprocess.run(
            ["docker", "compose", "--project-directory", str(self.directory),
             "--file", str(self.directory / "compose.yaml"), "config", "--format", "json"],
            cwd=self.directory, env=self.environment | keys, capture_output=True, text=True,
        )

    def write_dotenv(self):
        (self.directory / ".env").write_text(
            "".join(f"{name}={value}\n" for name, value in self.test_keys.items()),
        )

    def assert_app_keys(self, result, expected):
        self.assertEqual(0, result.returncode, result.stderr)
        environment = json.loads(result.stdout)["services"]["app"]["environment"]
        self.assertEqual(expected, {name: environment[name] for name in self.key_names})

    def test_shell_keys_are_passed_to_app(self):
        self.assert_app_keys(self.compose_config(self.test_keys), self.test_keys)

    def test_dotenv_keys_are_passed_to_app(self):
        self.write_dotenv()
        self.assert_app_keys(self.compose_config({}), self.test_keys)

    def test_shell_key_overrides_dotenv(self):
        self.write_dotenv()
        overrides = {self.key_names[0]: base64.b64encode(bytes(range(64, 96))).decode("ascii")}
        self.assert_app_keys(self.compose_config(overrides), self.test_keys | overrides)

    def test_missing_or_empty_key_is_rejected_without_revealing_other_key(self):
        for name in self.key_names:
            for value in (None, ""):
                with self.subTest(key=name, value=value):
                    keys = self.test_keys.copy()
                    if value is None:
                        del keys[name]
                    else:
                        keys[name] = value
                    result = self.compose_config(keys)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(name, result.stderr)
                    for secret in self.test_keys.values():
                        self.assertNotIn(secret, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
