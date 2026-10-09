# 🥋 Morning Pilates Bot

Telegram-бот для BJJ-группы. Цель проекта — сохранять техники с тренировок,
находить их в чате и получать цитаты тренера.

## Что реализовано

- Подключение к Telegram через long polling.
- Команда `/start`: бот отвечает «Morning Pilates Bot работает 🥋».
- Чтение секции `telegram` в типизированный `TelegramProperties`.
- Запуск бота вместе с Ktor и остановка polling при остановке приложения.
- HTTP-маршрут `GET /health`, который возвращает `200 OK` и текст `OK`.
- Локальный запуск приложения и PostgreSQL 18 через Docker Compose.
- Типизированные настройки БД, пул HikariCP и доступ к PostgreSQL через jOOQ.
- Запуск Flyway до Telegram polling и закрытие пула при завершении Ktor.
- Локальный конфиг, подключаемый в контейнер только на время запуска.
- Логирование в stdout, удалённая отладка JVM и GitHub Actions для сборки.
- Потокобезопасное хранилище пользовательских сессий в памяти на `ConcurrentHashMap`.
- Типизированные состояния добавления тренировки и модели `TrainingDetails` и `TechniqueDraft`.

Приложение подключается к PostgreSQL при старте. Ошибка подключения или миграции
прерывает запуск до Telegram polling. Flyway хранит историю в `flyway_schema_history`.
Продуктовых таблиц и репозиториев пока нет; SQL-миграции будут добавляться в
`src/main/resources/db/migration` вместе с продуктовыми функциями.
`/health` подтверждает работу HTTP-сервера; подключение к БД и Telegram он не проверяет.
Корневой маршрут `/` пока возвращает `404`.

## Планируемый MVP

### Сохранение техник

- Администратор добавляет описание техники в личном чате с ботом.
- Участники группы запрашивают сохранённые техники в групповом чате.

### Цитаты тренера

- Участник группы вызывает команду.
- Бот отправляет цитату тренера в чат.

Эти возможности и проверка ролей пока не реализованы.

## Стек

- Kotlin 2.4.20, JDK 25 LTS.
- Ktor 3.6.0.
- kotlinx.serialization 1.11.0.
- [kotlin-telegram-bot 10.0.0](https://github.com/kotlin-telegram-bot/kotlin-telegram-bot/releases/tag/10.0.0).
- PostgreSQL 18, JDBC-драйвер 42.7.13.
- jOOQ 3.21.9, HikariCP 7.1.0.
- Flyway 12.11.0 с поддержкой PostgreSQL.
- Logback 1.6.5, SLF4J.
- Gradle 9.3.0, Kotlin DSL.
- JUnit 6.1.3, Testcontainers 2.0.5 для тестов PostgreSQL.

Версии библиотек и плагинов находятся в `gradle/libs.versions.toml`.

## Структура приложения

```text
src/main/kotlin/morningpilates/
├── Application.kt
├── config/
│   ├── DatabaseProperties.kt
│   └── TelegramProperties.kt
├── telegram/
│   ├── BotLifecycle.kt
│   ├── TelegramBot.kt
│   └── command/
│       └── StartCommand.kt
├── health/
│   └── HealthRoutes.kt
├── flow/
│   ├── FlowState.kt
│   ├── UserSession.kt
│   ├── FlowStorage.kt
│   └── FlowSessionManager.kt
├── training/
│   ├── Technique.kt
│   ├── TechniqueService.kt
│   ├── TechniqueRepository.kt
│   ├── TrainingDetails.kt
│   └── TrainingFlowState.kt
└── infrastructure/
    ├── InMemoryFlowStorage.kt
    └── persistence/
        ├── Database.kt
        ├── PostgresDatabase.kt
        └── jooq/
```

`config` содержит типизированные настройки, `telegram` — взаимодействие с Telegram,
`health` — HTTP-проверку состояния. `flow` задаёт общий контракт состояний и хранения
пользовательских сессий. `training` содержит состояния добавления тренировки,
модели `TrainingDetails` и `TechniqueDraft` в файле `TrainingDetails.kt`, а также
пустые каркасы `Technique`, `TechniqueService` и `TechniqueRepository`.

В `infrastructure` находится реализация хранилища сессий в памяти.
В `infrastructure.persistence` находятся пул БД, Flyway и доступ к jOOQ.
Каталог `jooq` зарезервирован для реализации доступа к данным;
`.gitkeep` сохраняет пустой каталог в Git.

Основная конфигурация — `src/main/resources/application.conf`.
Параметр `telegram.botToken` читается из `TELEGRAM_BOT_TOKEN` либо локального конфига.
Токен и пароль БД обязательны для запуска приложения.

Секция `db` содержит `host`, `port`, `database`, `user`, `password`, `sslMode`
и `maximumPoolSize`. Основной конфиг читает переменные `DB_HOST`, `DB_PORT`,
`DB_DATABASE`, `DB_USER`, `DB_PASSWORD`, `DB_SSL_MODE` и `DB_POOL_SIZE`.
По умолчанию используются `postgres:5432`, база и пользователь `morning_pilates`,
SSL-режим `prefer` и пул максимум из пяти соединений. Пароль задаётся отдельно.
Для будущих репозиториев jOOQ доступен как `application.database.dsl`.

## Сессии и состояния тренировки

`UserSession` содержит состояние `FlowState` и время создания `createdAt: Instant`.
`FlowStorage` хранит не более одной активной сессии для каждого `userId: Long`.
Его реализация `InMemoryFlowStorage` использует `ConcurrentHashMap`:

| Метод | Поведение |
|---|---|
| `get(userId)` | Возвращает текущую сессию или `null`, если её нет. |
| `start(userId, session)` | Атомарно создаёт сессию, если записи ещё нет; возвращает `true`. При существующей сессии возвращает `false` и сохраняет её. |
| `update(userId, expected, updated)` | Атомарно заменяет сессию только при совпадении с `expected`. Возвращает `false` при отсутствии записи или несовпадении. |
| `remove(userId, expected)` | Атомарно удаляет сессию только при совпадении с `expected`. Возвращает `false` при отсутствии записи или несовпадении. |

Сравнение с `expected` использует равенство `UserSession`, включая `state` и `createdAt`.
Это предотвращает обновление или удаление, если текущая сессия уже отличается от `expected`.
Сессии хранятся в памяти одного экземпляра приложения и теряются при его перезапуске.

`TrainingFlowState` — `sealed interface`, наследующий `FlowState`.
Модель использует Type-State Pattern: каждый тип состояния определяет,
какие данные обязательны на этом этапе.

| Состояние | Обязательные данные |
|---|---|
| `WaitingDate` | Данных нет. |
| `WaitingStartTime` | `date: LocalDate`. |
| `WaitingAddTechnique` | `details: TrainingDetails`. |

В `TrainingDetails` обязательны `date: LocalDate` и `time: LocalTime`.
`techniques: List<TechniqueDraft>` по умолчанию пуст, `note: String?` — `null`.
У `TechniqueDraft` обязательное поле `name: String` и необязательное
`description: String?`, по умолчанию `null`.

Переходы между состояниями и обработчики добавления тренировки пока не реализованы.
`FlowSessionManager` остаётся пустым каркасом; состояния и хранилище сессий
пока не подключены к командам Telegram.

## Локальный запуск через Docker Compose

Нужен Docker с поддержкой Compose. JDK и Gradle на компьютере для этого способа
не требуются: приложение собирается внутри Docker.

1. Создайте локальный файл:

   ```sh
   touch src/main/resources/application-local.conf
   ```

2. Укажите в нём токен своего бота, полученный у BotFather, и локальные настройки БД:

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

Это локальные учётные данные из `compose.yaml`.
Имя `postgres` используется приложением внутри Docker-сети. Для DBeaver,
работающего на компьютере, используется `127.0.0.1`.

## Отладка контейнера в IntelliJ IDEA

`compose.debug.yaml` добавляет JVM-параметры отладки и публикует порт `5005`
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

## Запуск из IDEA или терминала

Установите JDK 25 и укажите его в `JAVA_HOME` или настройках Gradle JVM в IDEA.
Для полной сборки нужен работающий Docker: `build` включает интеграционные тесты.
Собрать проект:

```sh
./gradlew build
```

Сначала запустите PostgreSQL:

```sh
docker compose up -d postgres
```

Запустить с основным и локальным конфигами из корня проекта.
Параметры `-P:` заменяют контейнерный адрес БД на адрес с компьютера:

```sh
./gradlew run --args='-config=application.conf -config=src/main/resources/application-local.conf -P:db.host=127.0.0.1 -P:db.port=5432'
```

Если изменён `POSTGRES_PORT`, укажите его в `-P:db.port`.
Либо задайте `TELEGRAM_BOT_TOKEN`, `DB_HOST=127.0.0.1`, `DB_PORT`, `DB_DATABASE`,
`DB_USER` и `DB_PASSWORD` в окружении процесса и запустите `./gradlew run` без локального конфига.
При запуске из IDEA эти же параметры конфигурации можно указать в Program arguments,
а Working directory установить в корень проекта.

## Логирование

Logback пишет логи в stdout: они доступны через `docker compose logs -f app`
и в логах сервиса на Render. Конфигурация — `src/main/resources/logback.xml`.
Уровень по умолчанию — `INFO`, время выводится в UTC.

Для изменения уровня задайте `LOG_LEVEL`, например `DEBUG`, в `environment`
сервиса `app` в Compose или в Environment сервиса на Render.
Логирование HTTP-запросов пока не подключено.

## CI

GitHub Actions выполняет `./gradlew clean build` на Temurin JDK 25
при push в `main` и pull request в `main`. Конфигурация находится
в `.github/workflows/ci.yaml`. Gradle-кеш настраивается через `setup-gradle`.
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

## Теги релизов и ручной деплой

После push или merge в `main` workflow `CI` запускает проверки.
В `CI` есть только job `build`; пропущенной job создания тега в проверках PR нет.
После успешного CI для push в `main` отдельный workflow `Tag` создаёт аннотированный
Git-тег. Он запускается через `workflow_run`, повторной сборки не выполняет и берёт
SHA проверенного коммита из завершившегося CI, а не текущую вершину `main`.
Тег — указатель на коммит, отдельная ветка для него не создаётся.
На pull request теги не создаются. При неуспешном CI job в отдельном workflow `Tag`
не выполняется. Workflow `Tag` должен быть в `main`, чтобы обработать завершение CI.

Первый тег — `v0.1.0`. Далее версия определяется по сообщениям коммитов
после предыдущего тега:

- `feat:` или `feat(scope):` увеличивает minor: `v0.1.4` → `v0.2.0`;
- `!` перед двоеточием или footer `BREAKING CHANGE:` увеличивает major;
- остальные изменения, включая `fix:`, `chore:` и `docs:`, увеличивают patch.

Если изменения требуют разных повышений, выбирается самое большое.
При squash merge используйте Conventional Commit в заголовке PR/итогового коммита.
Повторный запуск CI сохраняет существующий тег. Если CI старого коммита завершится
после релиза более нового коммита, новый тег для старого кода не создаётся.
Тег определяет версию релиза; версия Gradle пока остаётся `0.1.0-SNAPSHOT`.
Правила репозитория должны разрешать GitHub Actions создавать теги `v*`.

Workflow `Deploy to Render` запускается при ручной публикации GitHub Release:

1. На Render подключите репозиторий и ветку `main`, установите Auto-Deploy в `Off`.
2. Скопируйте Deploy Hook URL из Settings сервиса.
3. В GitHub → Settings → Secrets and variables → Actions добавьте repository secret
   `RENDER_DEPLOY_HOOK_URL` со значением этого URL. В репозиторий URL не добавляется.
4. В Render → Account Settings → API Keys создайте ключ и добавьте его в GitHub
   repository secret `RENDER_API_KEY`. Он нужен для чтения результата деплоя.
   ID сервиса автоматически извлекается из hook URL; отдельно задавать его не нужно.
5. Влейте изменения в `main`, дождитесь успешного CI и автоматически созданного тега.
6. Откройте GitHub → **Releases → Draft a new release**.
7. В **Choose a tag** выберите существующий тег из списка. Новый тег вручную создавать
   не нужно — версии уже назначает CI. Укажите заголовок и описание релиза.
8. Нажмите **Publish release**: это запускает деплой выбранного тега в Render.

**Save draft** и **Update release** деплой не запускают. Публикация pre-release
тоже запускает событие `published` и деплой в тот же сервис Render.
Создание тега после merge само по себе не разворачивает приложение и не создаёт
GitHub Release. Дополнительной сборки в GitHub при публикации релиза нет.

GitHub использует workflow из коммита выбранного тега. Поэтому публикация релиза
запускает этот деплой для тегов, созданных после добавления обработчика `release`.
Для более старых тегов, повторного деплоя и отката оставлен ручной workflow:
**Actions → Deploy to Render → Run workflow → main**, затем поле `tag`.
Это запускает текущий workflow и скрипты из `main`, сохраняя выбранный коммит
приложения. Запуск ручного workflow из других веток или тегов запрещён.

Workflow проверяет принадлежность коммита тега истории `main` и успешный CI для
этого коммита, затем вызывает hook с `ref=<полный SHA>`.
Render собирает и разворачивает выбранный коммит, даже если `main` уже обновился.
HTTP `200` означает запуск деплоя, `202` — постановку в очередь.
Workflow проверяет доступ к Render API перед вызовом hook, затем ждёт до 10 минут,
проверяя статус каждые 15 секунд. Успех означает статус `live` именно у нового деплоя
выбранного коммита. Ошибка сборки, pre-deploy, запуска, отмена или таймаут завершают
workflow ошибкой. При `202` скрипт ждёт появления нового hook-деплоя с нужным SHA;
старые деплои того же коммита не считаются успехом. Если найдено несколько новых
совпадений, результат не подтверждается, чтобы не отслеживать чужой запуск.
В логах видны этапы, в Summary — результат, SHA и ссылка на сервис Render.
При HTTP-ошибке выводятся этап (API или hook), код ответа и сообщение Render
с удалёнными секретами. История деплоев читается с пагинацией без фильтра по дате:
существующие ID запоминаются до вызова hook, чтобы не принять старый запуск за новый.
Hook URL и API key не выводятся. POST не повторяется автоматически.
При таймауте деплой может продолжаться в Render — перед повторным запуском проверьте
Deploys сервиса. Статус `live` подтверждает завершение деплоя, а не бессрочную
работоспособность приложения. При откате можно выбрать предыдущий тег, если
изменения схемы БД совместимы с прежним кодом.

Проверить скрипты локально без обращения к GitHub и Render:

```sh
python3 -m unittest discover -s .github/scripts/tests
```

Документация: [Deploy Hooks](https://render.com/docs/deploy-hooks),
[Deploying a specific commit](https://render.com/docs/deploys#deploying-a-specific-commit),
[Render API](https://render.com/docs/api),
[Retrieve deploy](https://api-docs.render.com/reference/retrieve-deploy),
[GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository),
[Release workflow event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release).

## Развёртывание на Render

Для production используется тот же `Dockerfile`: сборка выполняется на JDK 25,
а приложение работает на JRE 25 от пользователя без root. Compose и локальный
конфиг для этого запуска не используются.

Создайте **Web Service** из репозитория, выберите ветку, **Language: Docker**
и **Dockerfile Path: `./Dockerfile`**. Команда запуска уже указана в образе.
В Environment сервиса задайте `TELEGRAM_BOT_TOKEN`.

Ktor слушает `0.0.0.0` и порт из переменной `PORT`, которую задаёт Render.
Для HTTP-проверки готовности укажите **Health Check Path: `/health`**.

Создайте отдельную Render Postgres в том же регионе и задайте переменные окружения приложения:

- `DB_HOST` — внутренний hostname Render Postgres;
- `DB_PORT` — `5432`;
- `DB_DATABASE` — имя базы;
- `DB_USER` и `DB_PASSWORD` — учётные данные Render Postgres;
- `DB_SSL_MODE` — `require`;
- `DB_POOL_SIZE` — максимальный размер пула, по умолчанию `5`.

Локальный конфиг на Render не подключается. Flyway запускается автоматически перед ботом.

Документация: [Docker](https://render.com/docs/docker),
[Web Services](https://render.com/docs/web-services),
[Health Checks](https://render.com/docs/health-checks),
[Postgres](https://render.com/docs/postgresql-creating-connecting).