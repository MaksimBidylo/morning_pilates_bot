# Локальная разработка

[README](../README.md) · [Архитектура](architecture.md) · [Развёртывание](deployment.md)

Все команды выполняются из корня репозитория.

## Требования к окружению

- Docker с поддержкой Compose — для контейнерного запуска и тестов PostgreSQL.
- JDK 25 — для запуска Gradle и приложения на компьютере.
- IntelliJ IDEA — при работе через IDE и подключении отладчика.
- Python 3 — для локальной проверки скриптов релиза и деплоя;
  команды приведены в [документации по развёртыванию](deployment.md#проверка-скриптов).
- Токен бота, полученный у BotFather.

Отдельная установка Gradle не требуется: используется Wrapper `./gradlew`.
Версии библиотек и плагинов заданы в
[gradle/libs.versions.toml](../gradle/libs.versions.toml), версия Gradle — в
[gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties).
Дополнительно используются kotlinx.serialization, HikariCP, Logback/SLF4J
и JDBC-драйвер PostgreSQL. Для Telegram используется библиотека
[kotlin-telegram-bot](https://github.com/kotlin-telegram-bot/kotlin-telegram-bot/releases/tag/10.0.0).

## Конфигурация

Основная конфигурация — [application.conf](../src/main/resources/application.conf).
Параметр `telegram.botToken` читается из `TELEGRAM_BOT_TOKEN` либо локального конфига.
Токен и пароль БД обязательны для запуска приложения.
При запуске из IDEA или терминала параметры подключения к БД нужно задать
в `application-local.conf` или окружении процесса. Compose передаёт их контейнеру `app`.

Секция `db` содержит `host`, `port`, `database`, `user`, `password`, `sslMode`
и `maximumPoolSize`. Основной конфиг читает переменные `DB_HOST`, `DB_PORT`,
`DB_DATABASE`, `DB_USER`, `DB_PASSWORD`, `DB_SSL_MODE` и `DB_POOL_SIZE`.
По умолчанию используются `postgres:5432`, база и пользователь `morning_pilates`,
SSL-режим `prefer` и пул максимум из пяти соединений. Пароль задаётся отдельно.
Для будущих репозиториев jOOQ доступен как `application.database.dsl`.

## Локальный запуск через Docker Compose

Нужен Docker с поддержкой Compose. JDK и Gradle на компьютере для этого способа
не требуются: приложение собирается внутри Docker.

1. Создайте локальный файл:

   ```sh
   touch src/main/resources/application-local.conf
   ```

2. Укажите в нём токен своего бота, полученный у BotFather.
   Для запуска через Compose блок `db` необязателен: параметры БД уже заданы
   в [compose.yaml](../compose.yaml). Полный пример с настройками локальной БД:

   ```hocon
   telegram {
       botToken = "YOUR_TELEGRAM_BOT_TOKEN"
   }
   db {
       host = "postgres"
       port = 5432
       database = "morning_pilates"
       user = "morning_pilates"
       password = "morning_pilates"
   }
   ```

3. Соберите образ и запустите приложение с PostgreSQL:

   ```sh
   docker compose up --build -d
   docker compose logs -f app
   ```

4. Проверьте HTTP-сервер и отправьте своему боту `/start`:

   ```sh
   curl http://127.0.0.1:8080/health
   ```

Compose загружает основной `application.conf` из образа, затем подключённый
`application-local.conf`. Совпадающие настройки из локального файла имеют приоритет.
Локальный файл подключается только для чтения и исключён из Git и Docker-образа.
Если файла нет, Compose сообщит об ошибке подключения.

Приложение запускается после успешной проверки готовности PostgreSQL.
Compose передаёт параметры подключения к БД. Настройки в локальном конфиге
имеют приоритет над значениями основного конфига, полученными из окружения.

Остановить приложение и PostgreSQL:

```sh
docker compose down
```

Данные PostgreSQL сохраняются в volume `postgres_data` после этой команды.

Если порты заняты, измените порты на компьютере:

```sh
APP_PORT=8081 POSTGRES_PORT=5433 docker compose up --build -d
```

Первое создание образа скачивает Gradle и зависимости. Следующие сборки используют
Docker-слои и Gradle-кеш, пока они сохраняются. Изменение локального конфига не требует
пересборки образа; после него перезапустите приложение: `docker compose restart app`.

## Подключение к PostgreSQL из DBeaver

Выберите драйвер PostgreSQL и укажите:

| Параметр | Значение |
|---|---|
| Host | `127.0.0.1` |
| Port | `5432` или выбранный `POSTGRES_PORT` |
| Database | `morning_pilates` |
| Username | `morning_pilates` |
| Password | `morning_pilates` |

Это локальные учётные данные из [compose.yaml](../compose.yaml).
Имя `postgres` используется приложением внутри Docker-сети. Для DBeaver,
работающего на компьютере, используется `127.0.0.1`.

## Запуск из IDEA или терминала

Установите JDK 25 и укажите его в `JAVA_HOME` или настройках Gradle JVM в IDEA.
Для полной сборки нужен работающий Docker: `build` включает интеграционные тесты.
Собрать проект:

```sh
./gradlew build
```

Приложению нужна работающая PostgreSQL — установленная локально или на доступном сервере.
Если используете PostgreSQL из Compose, запустите только контейнер БД:

```sh
docker compose up -d postgres
```

Для запуска из IDEA или терминала укажите токен и параметры выбранной БД
в `application-local.conf`. Для PostgreSQL из Compose подойдут настройки из полного
примера выше, но приложению на компьютере нужен адрес `127.0.0.1`
и опубликованный порт БД.

Команда запуска с основным и локальным конфигами выполняется из корня проекта.
Параметры `-P:` в примере заменяют контейнерный адрес БД на адрес с компьютера:

```sh
./gradlew run --args='-config=application.conf -config=src/main/resources/application-local.conf -P:db.host=127.0.0.1 -P:db.port=5432'
```

Если изменён `POSTGRES_PORT`, укажите его в `-P:db.port`.
Либо задайте `TELEGRAM_BOT_TOKEN`, `DB_HOST=127.0.0.1`, `DB_PORT`, `DB_DATABASE`,
`DB_USER` и `DB_PASSWORD` в окружении процесса и запустите `./gradlew run` без локального конфига.
При запуске из IDEA эти же параметры конфигурации можно указать в Program arguments,
а Working directory установить в корень проекта.

## Отладка контейнера в IntelliJ IDEA

[compose.debug.yaml](../compose.debug.yaml) добавляет JVM-параметры отладки и публикует порт `5005`
на `127.0.0.1`. Запустите приложение с обоими файлами:

```sh
docker compose -f compose.yaml -f compose.debug.yaml up --build -d app
```

В IDEA откройте **Run → Edit Configurations → + → Remote JVM Debug**:

- режим: **Attach to remote JVM**;
- Host: `127.0.0.1`;
- Port: `5005`;
- classpath module: модуль приложения.

Запустите конфигурацию отладки, поставьте breakpoint в обработчике `/start`
и отправьте команду боту. В логах контейнера при запуске должно быть
`Listening for transport dt_socket at address: 5005`.

В debug-конфиге используется `suspend=n`: приложение запускается до подключения IDEA.
Для отладки самого старта замените его на `suspend=y`, чтобы JVM дождалась отладчика
перед выполнением `main()`.

Исходники в IDEA должны соответствовать коду в образе. После изменения кода повторите
команду с `--build` и подключите отладчик заново.
Обычный запуск `docker compose up -d app` пересоздаст приложение без debug-настроек.

[Remote JVM Debug в IntelliJ IDEA](https://www.jetbrains.com/help/idea/tutorial-remote-debug.html).

## Логирование

Logback пишет логи в stdout: они доступны через `docker compose logs -f app`
и в логах сервиса на Render. Конфигурация — [logback.xml](../src/main/resources/logback.xml).
Уровень по умолчанию — `INFO`, время выводится в UTC.

Для изменения уровня задайте `LOG_LEVEL`, например `DEBUG`, в `environment`
сервиса `app` в Compose или в Environment сервиса на Render.
Логирование HTTP-запросов пока не подключено.

## Тесты и Gradle

HTTP-тест `/health` проверяет статус `200 OK`. Тесты `/start` проверяют
приветствие и отправку ответа в исходный личный или групповой чат.
Тесты конфигурации проверяют чтение `telegram.botToken`, приоритет локального
конфига и отказ запуска приложения без токена. Тесты жизненного цикла проверяют
однократный запуск бота вместе с Ktor и остановку бота при завершении Ktor.
ArchUnit проверяет границы пакетов и отсутствие циклических зависимостей.
Тесты `InMemoryFlowStorage` проверяют создание, условное обновление и удаление
сессий, независимость пользователей, отказ при устаревшем `expected` и работу
с отсутствующими записями. Конкурентные сценарии используют два реальных потока
с общим сигналом старта и проверяют единственного победителя.
Тесты `TrainingFlowService` проверяют переходы всех состояний, корректный и
некорректный ввод, сохранность данных, подтверждение, отмену и результаты сохранения
через `FakeTrainingRepository`. Тесты `FlowSessionManager` проверяют выбор обработчика,
конфликты условного обновления и удаления, порядок перехода и продолжения,
повторный выбор обработчика и сохранение `createdAt`.
`TrainingFlowSessionIntegrationTest` проверяет связку реальных сервиса, менеджера
и хранилища сессий в памяти с тестовым репозиторием для сохранения тренировки.
Он входит в обычную задачу `test`; отдельный тег `integration` используется для тестов БД.

Обычные тесты работают без обращения к Telegram и БД:

```sh
./gradlew test
```

Запустить только тесты хранилища сессий, включая вложенные группы `Start`, `Update` и `Remove`:

```sh
./gradlew test --tests 'morningpilates.infrastructure.InMemoryFlowStorageTest*'
```

Тесты БД запускают временный PostgreSQL 18 через Testcontainers. Нужен работающий Docker:

```sh
./gradlew integrationTest
```

Они проверяют запросы jOOQ, применение миграций один раз, откат транзакции,
закрытие пула при ошибке миграции и при остановке Ktor. Контейнер и данные удаляются
после тестов; база Docker Compose не используется. Первая проверка может скачать
образы PostgreSQL и служебного контейнера Testcontainers.
`./gradlew build` выполняет оба набора тестов, в том числе в CI.

## Статический анализ Kotlin

Проверить форматирование и стиль Kotlin-кода:

```sh
./gradlew ktlintCheck
```

Запустить статический анализ:

```sh
./gradlew detekt
```

ktlint проверяет Kotlin-исходники, тесты и Gradle Kotlin DSL по стилю
`ktlint_official`, заданному в [.editorconfig](../.editorconfig).
Плагин ktlint 14.2.0 использует явно закреплённый engine 1.8.0.
Для detekt выбран 2.0.0-alpha.6: это предварительный релиз ветки 2.0,
[проверяемый upstream на JDK 25](https://detekt.dev/docs/introduction/compatibility/).
Анализаторы используют собственные версии Kotlin compiler; их зависимости
не подменяются версией Kotlin приложения.

Обе проверки временно работают в информационном режиме (`ignoreFailures = true`):
найденные нарушения попадают в логи и отчёты, но не блокируют сборку.
Ошибки запуска и конфигурации анализаторов по-прежнему приводят к падению.
Проверки автоматически подключены к задаче `check`, поэтому
`./gradlew clean build` выполняет их вместе с обычными и интеграционными тестами.
Автоматическое форматирование, baselines и подавления правил не настроены.
Отчёты ktlint находятся в `build/reports/ktlint/`, detekt — в `build/reports/detekt/`.
После исправления существующих нарушений нужно установить `ignoreFailures = false`
в блоках `ktlint` и `detekt` файла `build.gradle.kts`, чтобы включить блокирующий режим.

## Именование коммитов

Используем [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```text
<type>[(scope)][!]: <description>
```

Части в квадратных скобках необязательны. Тип пишем строчными буквами,
после двоеточия ставим пробел. `scope` обозначает затронутую часть проекта,
например `training`, `flow` или `telegram`. Описание кратко объясняет изменение.

| Тип | Когда использовать |
|---|---|
| `feat` | Новая функциональность. |
| `fix` | Исправление ошибки. |
| `docs` | Изменения документации. |
| `refactor` | Изменение структуры кода без изменения поведения. |
| `test` | Добавление или изменение тестов. |
| `chore` | Обслуживание репозитория, например настройка `.gitignore`. |
| `build` | Изменение сборки или зависимостей. |
| `ci` | Изменение CI/CD: workflows и скриптов автоматизации. |
| `perf` | Улучшение производительности. |
| `style` | Форматирование кода без изменения логики. |
| `revert` | Откат предыдущего изменения. |

Примеры:

```text
feat(training): add note input
fix(flow): reject stale session updates
docs: restructure README and add project documentation
```

Несовместимые изменения обозначаем `!` перед двоеточием,
например `refactor(flow)!: change FlowHandler contract`,
или отдельным footer `BREAKING CHANGE: описание несовместимого изменения`
после пустой строки. Footer начинается без отступа; причину несовместимости нужно пояснить.

При squash merge соблюдайте этот формат в заголовке PR и проверьте итоговый коммит.
Скрипт создания тегов определяет версию по сообщениям коммитов;
автоматической проверки их формата сейчас нет.
Влияние типов на версию описано в [правилах версий](deployment.md#правила-версий).

## CI

GitHub Actions выполняет `./gradlew clean build` на Temurin JDK 25
при push в `main` и pull request в `main`. Конфигурация находится
в [ci.yaml](../.github/workflows/ci.yaml). Gradle-кеш настраивается через `setup-gradle`.
Также CI выполняет Python-тесты скриптов создания тегов и деплоя.
Подробности выпуска приложения находятся в [Развёртывание](deployment.md).

[Kotlin Static Analysis](../.github/workflows/static-analysis.yaml) запускается
на pull request в `main` и push в `main`. Независимые jobs `ktlint` и `detekt`
используют Temurin JDK 25 и Gradle Wrapper; они выполняются параллельно
и не используют фильтрацию по изменённым файлам. Анализаторы также запускаются
в существующем job `build` через стандартную зависимость `check`.
Пока проверки информационные: нарушения видны в логах, но jobs остаются успешными,
если анализ завершился без ошибок запуска или конфигурации.
