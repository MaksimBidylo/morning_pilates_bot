#!/usr/bin/env python3
import argparse
import base64
import binascii
import getpass
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


KEY_NAMES = ("TELEGRAM_ID_ENCRYPTION_KEY", "TELEGRAM_ID_LOOKUP_KEY")


def dotenv_keys(text):
    keys = {}
    for line in text.splitlines():
        match = re.match(r"^\s*(?:export\s+)?(\w+)\s*=\s*(.*?)\s*$", line)
        if not match or match[1] not in KEY_NAMES:
            continue
        name, value = match.groups()
        if name in keys:
            raise ValueError(f"{name}: повторяющийся параметр в .env")
        if value.startswith(('"', "'")):
            quote = value[0]
            end = value.find(quote, 1)
            if end < 0 or (value[end + 1:].strip() and not value[end + 1:].lstrip().startswith("#")):
                raise ValueError(f"{name}: некорректная строка в .env")
            value = value[1:end]
        else:
            value = re.split(r"\s+#", value, maxsplit=1)[0].strip()
        keys[name] = value
    return keys


def validate_key(name, value):
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError(f"{name}: требуется Base64 от 32 байт") from None
    if len(decoded) != 32:
        raise ValueError(f"{name}: после декодирования должно быть ровно 32 байта")
    return decoded


def generate_key():
    result = subprocess.run(
        ["openssl", "rand", "-base64", "32"], check=True, capture_output=True, text=True,
    )
    return result.stdout.strip()


def write_private(path, text, project):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=project, prefix=".env.setup.",
                                         delete=False, encoding="utf-8") as file:
            temporary = Path(file.name)
            file.write(text)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def setup(project, environment, start=False):
    dotenv = project / ".env"
    local_config = project / "src/main/resources/application-local.conf"
    for path in (dotenv, local_config):
        if path.is_symlink():
            raise ValueError(f"{path.name}: ожидается обычный файл, а не символическая ссылка")
    original = dotenv.read_text() if dotenv.exists() else ""
    saved = dotenv_keys(original)
    keys = {}
    for name in KEY_NAMES:
        value = environment.get(name, saved.get(name))
        if name in environment and name in saved and environment[name] != saved[name]:
            raise ValueError(f"{name}: значения в окружении и .env различаются; устраните конфликт")
        if value is not None:
            validate_key(name, value)
            keys[name] = value
    for name in KEY_NAMES:
        if name not in keys:
            keys[name] = generate_key()
    decoded = [validate_key(name, keys[name]) for name in KEY_NAMES]
    if decoded[0] == decoded[1]:
        raise ValueError("Ключи шифрования и поиска должны различаться")

    config_text = local_config.read_text() if local_config.exists() else ""
    if not config_text.strip():
        token = environment.get("TELEGRAM_BOT_TOKEN") or getpass.getpass("Токен бота из BotFather (ввод скрыт): ")
        if not token.strip():
            raise ValueError("Токен бота не должен быть пустым")
        config_text = f"telegram.botToken = {json.dumps(token)}\n"

    additions = "".join(f"{name}={keys[name]}\n" for name in KEY_NAMES if name not in saved)
    if additions:
        separator = "\n" if original and not original.endswith("\n") else ""
        write_private(dotenv, original + separator + additions, project)
    dotenv.chmod(0o600)
    if not local_config.exists() or not local_config.read_text().strip():
        write_private(local_config, config_text, project)
    local_config.chmod(0o600)

    compose = ["docker", "compose", "--project-directory", str(project),
               "--file", str(project / "compose.yaml"), "--env-file", str(dotenv)]
    subprocess.run(compose + ["config", "--quiet"], env=environment, check=True, capture_output=True)
    print("Настройка готова. Ключи сохранены в .env; конфигурация Compose проверена.")
    if start:
        subprocess.run(compose + ["up", "--build", "-d"], env=environment, check=True)
    else:
        print("Для запуска: docker compose up --build -d")


def main():
    parser = argparse.ArgumentParser(description="Настроить ключи и локальный запуск Docker Compose")
    parser.add_argument("--up", action="store_true", help="после настройки собрать и запустить контейнеры")
    arguments = parser.parse_args()
    try:
        for executable in ("openssl", "docker"):
            if not shutil.which(executable):
                raise ValueError(f"Установите {executable} перед запуском")
        subprocess.run(["docker", "compose", "version"], check=True, capture_output=True)
        setup(Path(__file__).resolve().parents[1], dict(os.environ), start=arguments.up)
    except (ValueError, OSError, EOFError, subprocess.CalledProcessError) as failure:
        if isinstance(failure, ValueError):
            parser.exit(1, f"Ошибка настройки: {failure}\n")
        parser.exit(1, "Ошибка настройки: проверьте Docker Compose, OpenSSL и доступ к локальным файлам.\n")


if __name__ == "__main__":
    main()
