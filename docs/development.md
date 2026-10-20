# Локальная разработка

[README](../README.md) · [Пользовательские пути](user-flows.md) · [Архитектура](architecture.md) · [Развёртывание](deployment.md)

Все команды выполняются из корня репозитория. Для разработки используйте отдельный
тестовый токен и тестовую БД, не дневник с реальными пользовательскими данными.

## Требования

Docker с Compose нужен для контейнерного запуска и PostgreSQL/Testcontainers.
Python 3 и OpenSSL нужны для скрипта настройки. Для Gradle и запуска на компьютере
требуется JDK 25; для полностью контейнерного запуска локальный JDK не нужен.
IntelliJ IDEA необязательна и используется для разработки и отладки.

Gradle отдельно не устанавливается: используйте `./gradlew`. Версии библиотек —
в [gradle/libs.versions.toml](../gradle/libs.versions.toml), версия Gradle — в
[gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties).

## Конфигурация

Источник настроек — [application.conf](../src/main/resources/application.conf).
Локальный конфиг `src/main/resources/application-local.conf` не включён в Git
и загружается отдельно. При указании после основного конфига его значения имеют
приоритет, в том числе над значениями, прочитанными основным конфигом из окружения.

| Переменная | Назначение | Значение основного конфига по умолчанию |
|---|---|---|
| `TRAINING_TIME_PICKER_ENABLED` | Выбор времени кнопками в `/training_add` | `false` |
| `TRAINING_SEARCH_DATE_CALENDAR_ENABLED` | Inline-календарь даты в `/training_get_date` | `false` |
| `TRAINING_DATE_CALENDAR_ENABLED` | Inline-календарь даты в `/training_add` | `false` |
| `TELEGRAM_BOT_TOKEN` | Токен Telegram-бота | Обязателен, если не задан локальным конфигом |
| `TELEGRAM_ID_ENCRYPTION_KEY` | Base64-ключ шифрования Telegram ID | Обязателен |
| `TELEGRAM_ID_LOOKUP_KEY` | Независимый Base64-ключ поиска пользователя | Обязателен |
| `DB_HOST` | Адрес PostgreSQL | `postgres` |
| `DB_PORT` | Порт PostgreSQL | `5432` |
| `DB_DATABASE` | База | `morning_pilates` |
| `DB_USER` | Пользователь БД | `morning_pilates` |
| `DB_PASSWORD` | Пароль БД | Обязателен |
| `DB_SSL_MODE` | JDBC SSL-режим | `prefer` |
| `DB_POOL_SIZE` | Максимальный размер пула | `5` |
| `PORT` | Порт Ktor | `8080` |
| `LOG_LEVEL` | Уровень корневого логгера | `INFO`, задаётся в Logback |

`.env` автоматически читает Compose, но не Ktor, Gradle или IDEA. В штатном
`compose.yaml` токен поступает через примонтированный локальный конфиг, а не через
передачу `TELEGRAM_BOT_TOKEN` контейнеру. `setup_compose.py` может прочитать эту
переменную при первоначальном создании локального файла. При уже заполненном файле
скрипт не заменяет его содержимое новым токеном из окружения.

### Календарь даты тренировки

Временный feature flag `telegram.trainingDateCalendarEnabled` по умолчанию равен
`false`. Для включения задайте `TRAINING_DATE_CALENDAR_ENABLED=true` в окружении
приложения либо `telegram.trainingDateCalendarEnabled = true` в локальном конфиге.
Для выключения используйте `false` и перезапустите приложение. Последний
переданный конфиг имеет приоритет над основным `application.conf`.

В Compose добавьте `TRAINING_DATE_CALENDAR_ENABLED=true` в `.env` и пересоздайте
контейнер приложения (`docker compose up -d --build app`). Без настройки остаётся
прежний текстовый ввод; при выключенном флаге calendar callback не обрабатываются.
Описание навигации — в [пользовательских сценариях](user-flows.md#календарь-даты).

Календарь поиска управляется независимо: `TRAINING_SEARCH_DATE_CALENDAR_ENABLED`
(конфигурация `telegram.trainingSearchDateCalendarEnabled`), по умолчанию `false`.
Для проверки в тестовом окружении задайте `true` и перезапустите приложение;
в Compose переменная передаётся из `.env`. Значение `false` возвращает прежний
текстовый вопрос поиска. Этот флаг не включает календарь создания.

Не включайте новые интерфейсы для пользователей до завершения #149, #150, #151,
#153, общей ручной приёмки и отдельного решения о включении.

### Выбор времени тренировки

Независимый временный флаг `TRAINING_TIME_PICKER_ENABLED` (настройка
`telegram.trainingTimePickerEnabled`) по умолчанию `false`. Для проверки в тестовом
окружении задайте `true` и перезапустите приложение; в Compose переменная берётся
из `.env`. Для отключения задайте `false` и перезапустите приложение: останется
прежний текстовый вопрос времени, обработчик time-picker callback не регистрируется.
Календари создания и поиска этим флагом не включаются. Правило общей приёмки
и отдельного решения о включении выше относится и к выбору времени.

### Ключи и секреты

Оба ключа после Base64-декодирования должны содержать ровно 32 байта и различаться.
Скрипт настройки создаёт недостающие ключи через отдельные вызовы OpenSSL, сохраняет
существующие и отклоняет конфликт между окружением и `.env`.

Сохраняйте ключи вместе с окружением: смена encryption key нарушает расшифровку
прежних ID, смена lookup key — поиск прежних пользователей. Ротация не реализована.
Для восстановления нужны резервная копия БД и исходные ключи. Шифруется Telegram ID,
не всё содержимое тренировок.

Не публикуйте `.env`, локальный конфиг, токены, ключи, пароли и полный Deploy Hook URL.
`.env` и локальный конфиг исключены из Git и Docker build context.
Для проверки Compose используйте `docker compose config --quiet`: обычный вывод
`docker compose config` может содержать подставленные секреты.

## Запуск через Docker Compose

```sh
./scripts/setup_compose.py --up
curl --fail http://127.0.0.1:8080/health
docker compose logs -f app
```

[Скрипт настройки](../scripts/setup_compose.py) создаёт `.env` и при необходимости
локальный конфиг, задаёт права `600`, проверяет Compose, затем выполняет
`docker compose up --build -d`. Без `--up` он только подготавливает файлы и проверяет
конфигурацию. Существующий непустой локальный конфиг сохраняется.

[Compose](../compose.yaml) поднимает PostgreSQL 18, ждёт его healthcheck и запускает
приложение. В образе используется основной конфиг; локальный файл примонтирован
read-only. При отсутствии файла подключение завершается ошибкой.
Настройки локальной БД: `postgres:5432`, база/пользователь/пароль `morning_pilates`.
Эти учётные данные предназначены только для локального окружения.

Проверка `/health` должна вернуть `OK`. Затем проверьте реальный сценарий бота:
`/start` → `/training_add` → дата и время → сохранение → `/training_get_last`.
Полные сценарии создания, поиска и удаления описаны в
[пользовательских путях](user-flows.md).

Обычный последующий запуск и остановка:

```sh
docker compose up --build -d
docker compose down
```

Остановка не удаляет volume `postgres_data`. Не добавляйте `--volumes`/`-v` к команде
остановки, если данные нужно сохранить.

При занятых портах:

```sh
APP_PORT=8081 POSTGRES_PORT=5433 docker compose up --build -d
```

Меняются порты компьютера, а не адрес PostgreSQL внутри сети Compose.
После изменения локального конфига достаточно `docker compose restart app`;
после изменения исходников требуется пересборка. Рестарт сбрасывает черновики
и просмотры; сохранённые тренировки остаются в БД.

## PostgreSQL из DBeaver или psql

С компьютера подключайтесь к `127.0.0.1:5432` либо выбранному `POSTGRES_PORT`.
База, пользователь и пароль локального Compose — `morning_pilates`.
`postgres` — имя сервиса внутри Docker-сети, не адрес для DBeaver на компьютере.

Проверить сохранённые записи без раскрытия Telegram ID можно чтением таблиц
`trainings` и `training_techniques`; связь — `training_id`, порядок техник — `position`.
Удаление из приложения проверяйте новым чтением после действия. Не пытайтесь
удалять родительскую строку вручную без учёта техник: внешние ключи не настроены
на каскадное удаление. Для обычного тестирования используйте кнопку бота.

## Запуск из IDE или терминала

Укажите JDK 25 в `JAVA_HOME` и Gradle JVM IDE. Для PostgreSQL из Compose сначала
подготовьте окружение: Compose требует ключи даже при запуске только сервиса БД.
Не запускайте одновременно контейнер `app` и локальный бот с тем же токеном.

```sh
./scripts/setup_compose.py
docker compose stop app
docker compose up -d postgres
```

Экспортируйте ключи из **собственного доверенного** `.env`:

```sh
set -a
. ./.env
set +a
```

При локальном файле с токеном запустите:

```sh
./gradlew run --args='-config=application.conf -config=src/main/resources/application-local.conf -P:db.host=127.0.0.1 -P:db.port=5432 -P:db.password=morning_pilates'
```

Пароль в примере относится только к локальной БД Compose. Если её настройки
изменены, передайте правильные значения через локальную конфигурацию или окружение;
не размещайте production-пароль в командной строке. При другом `POSTGRES_PORT`
замените `-P:db.port`.

Альтернатива — задать все обязательные значения таблицы конфигурации в окружении,
включая `DB_HOST=127.0.0.1` и `DB_PASSWORD`, затем выполнить `./gradlew run`.
Без `-config` локальный файл автоматически не подключается.
В IDE задайте рабочий каталог корнем репозитория, те же аргументы и переменные,
не сохраняя секреты в общей Run Configuration.

## Отладка и логи

[compose.debug.yaml](../compose.debug.yaml) включает JDWP и публикует порт только
на loopback компьютера:

```sh
docker compose -f compose.yaml -f compose.debug.yaml up --build -d app
```

В IDEA создайте Remote JVM Debug, Attach, `127.0.0.1:5005`, и подключитесь.
Используется `suspend=n`: приложение не ждёт отладчика при запуске. После пересборки
подключитесь заново; исходники должны соответствовать образу. Для обычного запуска
используйте только `compose.yaml`. Debug-порт не предназначен для публикации наружу.

[Logback](../src/main/resources/logback.xml) пишет в stdout с временем UTC;
`io.netty` ограничен уровнем `WARN`. Логи контейнера:

```sh
docker compose logs --tail=100 app
docker compose logs -f app
```

`LOG_LEVEL` нужно передать самому процессу: через окружение IDE/Render или
`environment` сервиса Compose. Простое добавление произвольной переменной в `.env`
не передаёт её контейнеру, если она не используется в Compose-конфигурации.
Не публикуйте необработанные debug-логи с пользовательскими данными и секретами.

## Тесты и анализаторы

Обычная задача `test` исключает тег `integration`. Задача `integrationTest` использует
тот же тестовый source set, но запускает только тесты с этим тегом.
Источник команд и зависимостей задач — [build.gradle.kts](../build.gradle.kts).

| Проверка | Команда | Требуется Docker daemon |
|---|---|---|
| Обычные тесты | `./gradlew test` | Нет |
| Telegram-сценарии | `./gradlew test --tests 'morningpilates.telegram.*'` | Нет |
| PostgreSQL/Testcontainers | `./gradlew integrationTest` | Да |
| Полная локальная сборка | `./gradlew clean build` | Да |
| Формат Kotlin | `./gradlew ktlintCheck` | Нет |
| Анализ Kotlin | `./gradlew detekt` | Нет |
| Исправление форматирования | `./gradlew ktlintFormat` | Нет |

Telegram-тесты используют реальный Dispatcher и тестовый HTTP-транспорт, а не
настоящий Telegram. БД-тесты используют изолированные контейнеры, не базу Compose.
Первый запуск может скачивать зависимости и образы. Ручная приёмка развёрнутого бота
описана в [чек-листе выпуска](deployment.md#приёмка-выпуска-mvp).

Сфокусированный запуск удаления в PostgreSQL:

```sh
./gradlew integrationTest --tests 'morningpilates.infrastructure.persistence.PostgresTrainingDeleteIntegrationTest'
```

Скрипты настройки, тегов, деплоя и проверки заголовка PR:

```sh
python3 -m unittest discover -s .github/scripts/tests
```

Эти тесты не обращаются к рабочим GitHub/Render API. Для Compose-проверок нужен
Docker Compose CLI, но не daemon. Python-тесты запускаются отдельно от Gradle;
CI выполняет их перед JVM-сборкой.

### Покрытие

`jacocoTestReport` объединяет `test` и `integrationTest`, поэтому требует Docker.
`jacocoTestCoverageVerification` проверяет не менее 80% покрытия строк всего
основного кода и подключён к `check`.

```sh
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
```

Отчёты: `build/reports/jacoco/combined/html/index.html` и
`build/reports/jacoco/combined/coverage.xml`.

## CI и внешние анализаторы

[CI](../.github/workflows/ci.yaml) запускается для push/PR в `main` и содержит
отдельные jobs `build`, `ktlint`, `detekt`. В job сборки анализаторы исключены
из Gradle-команды, чтобы не дублировать их; локальный `clean build` их не исключает.
`build` запускает оба набора JVM-тестов, проверку покрытия и Python-тесты, публикует
`jacoco-coverage`. Подготовка `sonar-inputs` отдельно помечена `continue-on-error`.
Пропуска JVM-тестов для docs-only PR в текущем workflow нет.

[SonarQube Cloud](../.github/workflows/sonar.yaml) работает после успешного CI
с artifact той же попытки: готовыми классами и объединённым XML JaCoCo.
Для fork/Dependabot предусмотрен пропуск анализа без передачи `SONAR_TOKEN`.
Исходники анализируются из проверенного CI коммита; Gradle-конфигурация сканера
берётся из доверенной main. Тесты и компиляция не запускаются повторно.

Для ручного локального анализа уже собранного проекта:

```sh
./gradlew clean build
./gradlew sonar
```

Вторая команда отправляет данные во внешний сервис и требует `SONAR_TOKEN`.
Она не нужна для обычной локальной разработки. `sonar.qualitygate.wait=true`
не задан в build script: результат Quality Gate проверяется отдельно в SonarQube Cloud.

[CodeQL](../.github/workflows/codeql.yaml) — отдельный анализ Java/Kotlin с ручной
компиляцией. Найденные замечания доступны в GitHub Code Scanning.

## Именование коммитов и PR

Используйте форму `<type>[(scope)][!]: <description>`, например:

```text
feat(training): add training deletion
fix(telegram): retire cancelled confirmation
docs: refresh MVP documentation
```

В репозитории есть отдельный workflow [PR title](../.github/workflows/pr-title.yaml),
проверяющий заголовок скриптом [validate_pr_title.py](../.github/scripts/validate_pr_title.py).
При squash merge проверяйте итоговый заголовок:
[tag.py](../.github/scripts/tag.py) определяет повышение версии по сообщениям
коммитов. Правила описаны в [развёртывании](deployment.md#правила-версий).
