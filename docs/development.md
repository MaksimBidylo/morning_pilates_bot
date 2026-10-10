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

## Покрытие тестами

Стандартный Gradle JaCoCo Plugin формирует единый отчёт для основного кода приложения
по результатам обычных тестов (`test`) и тестов БД (`integrationTest`). Версия JaCoCo 0.8.15
закреплена в Version Catalog; она поддерживает JDK 25.

Сформировать отчёт вместе со всеми проверками проекта:

```sh
./gradlew clean build
```

Запустить только тесты и генерацию покрытия:

```sh
./gradlew jacocoTestReport
```

Задача `jacocoTestReport` зависит от обоих наборов тестов, поэтому нужен работающий Docker.
Каждая тестовая задача выполняется не более одного раза в рамках одной сборки;
Gradle может использовать её актуальные результаты без повторного запуска.
JaCoCo автоматически инструментирует обе задачи `Test`. Они записывают данные
в разные файлы — `build/jacoco/test.exec` и `build/jacoco/integrationTest.exec`,
поэтому один набор не перезаписывает покрытие другого.

Готовые отчёты:

- HTML: `build/reports/jacoco/combined/html/index.html`.
- XML: `build/reports/jacoco/combined/coverage.xml`.

В отчёт входят классы основного source set, а не тестовые классы. Исключения для
классов, пакетов или методов не заданы. Обязательный порог покрытия строк — **80%**
для всего основного кода приложения (`BUNDLE`, `LINE`, `COVEREDRATIO`, `minimum = 0.80`).
Проверка использует объединённые данные `test` и `integrationTest`.

Проверить порог отдельно:

```sh
./gradlew jacocoTestCoverageVerification
```

Задача зависит от `jacocoTestReport`: сначала выполняются оба набора тестов и формируются
HTML/XML-отчёты, затем проверяется порог. При недостаточном покрытии отчёты остаются
доступны для просмотра и загрузки в CI. Проверка подключена к `check`, поэтому
`./gradlew clean build` завершается ошибкой при покрытии строк ниже 80%.

В GitHub Actions откройте запуск workflow **CI**, затем раздел **Artifacts** и скачайте
`jacoco-coverage`. Архив содержит `coverage.xml` и каталог `html`; после распаковки
откройте `html/index.html`. После успешной сборки CI проверяет наличие непустых XML- и
HTML-файлов по указанным выше путям; отсутствие или пустой файл приводят к падению job `build`.
Если сборка или проверка отчётов упала, уже сформированные отчёты всё равно загружаются.
Если отчётов ещё нет, шаг загрузки пропускается, а исходная ошибка сохраняется.

## SonarQube Cloud

SonarScanner for Gradle `org.sonarqube` версии `7.5.0.8588` подключён через Version Catalog.
Проект — `MaksimBidylo_morning_pilates_bot`, организация — `maksimbidylo`,
сервер — `https://sonarcloud.io`.

[CI](../.github/workflows/ci.yaml) по-прежнему выполняет `./gradlew clean build`:
тесты, ktlint, detekt, проверка покрытия JaCoCo 80% и проверка отчётов остаются обязательными.
После успешных проверок он передаёт классы, существующий объединённый XML и SHA
собранного коммита в artifact `sonar-inputs-<run_id>-<run_attempt>`.
Только новые шаги подготовки и загрузки этого artifact используют `continue-on-error`.
Их сбой не блокирует релизное тегирование; существующий artifact `jacoco-coverage`
и его правила публикации не изменены.

Отдельный workflow [SonarQube Cloud](../.github/workflows/sonar.yaml) запускается через
`workflow_run` после завершения CI. Для анализа нужен успешный CI: workflow скачивает
входные данные именно этой попытки CI и запускает `./gradlew sonar`, не повторяя компиляцию, тесты или генерацию JaCoCo.
При отсутствии artifact, классов, непустого XML, токена или при ошибке SonarScanner
этот workflow завершается с **Failure**. Он не является зависимостью CI, Tag или Deploy:
доступность SonarQube Cloud не определяет выпуск приложения. При неуспешном CI
обычный запуск Sonar workflow также завершается с Failure до запуска сканера.

Для PR из fork и от Dependabot job анализа получает **Skipped**, а Summary явно
сообщает причину: это не успешный анализ. Остальные проверки таких PR остаются обязательными.
Для обычного PR из текущего репозитория отсутствие `SONAR_TOKEN` — ошибка.
Происхождение PR проверяется по данным GitHub до доступа к секрету.
`pull_request_target` для запуска Gradle не используется.

Сканер анализирует исходники коммита, собранного CI, и читает готовые классы/XML из
временного каталога artifact. Wrapper, build scripts, версии плагинов и зависимости
сканера берутся из доверенной `main` — из коммита самого Sonar workflow.
Gradle-код из PR с токеном не выполняется. Поэтому изменения Gradle-конфигурации в PR
начинают использоваться сканером после попадания в `main`; для текущего Kotlin-модуля
пути исходников, тестов и классов задаются явно. PR number, branch, base и head SHA
передаются сканеру отдельно, поскольку событие `workflow_run` не является `pull_request`.

Локально сначала соберите проект, затем запустите сканер:

```sh
./gradlew clean build
./gradlew sonar
```

Команда `sonar` отправляет результаты в SonarQube Cloud. Для неё нужен токен
с правом анализа проекта в переменной окружения `SONAR_TOKEN`. Токен не записывают
в репозиторий и не передают аргументом командной строки. Для GitHub Actions пользователь
создаёт repository secret `SONAR_TOKEN` в **Settings → Secrets and variables → Actions**;
workflow передаёт его только в окружение шага сканера.

Используется существующий файл `build/reports/jacoco/combined/coverage.xml`, включающий
данные `test` и `integrationTest`. В отдельном workflow используется скачанная копия
этого же файла, а не новый отчёт. Проверить конфигурацию и граф без отправки анализа:

```sh
./gradlew help --task sonar
./gradlew sonar --dry-run
```

Порядок первого запуска:

1. Создайте repository secret `SONAR_TOKEN` с правом анализа проекта.
2. Отключите Automatic Analysis: **Administration → Analysis Method** в SonarQube Cloud.
   Одновременное использование Automatic Analysis и SonarScanner приводит к ошибке;
   импорт покрытия доступен при CI-based Analysis.
3. После ревью влейте интеграцию в `main`: новый `workflow_run` начинает работать,
   когда его файл присутствует на default branch. Дождитесь CI и отдельного Sonar workflow
   для `main`. Если `main` ещё не анализировалась, сначала должен пройти её анализ.
4. Затем проверьте обычный PR из этого репозитория: импорт покрытия и GitHub PR decoration.
   Само добавление workflow в ещё не влитый PR не обеспечивает его первый запуск.

После анализа найдите в логах **JaCoCo XML Report Importer** и убедитесь, что нет
предупреждения об отсутствующем отчёте. В SonarQube Cloud откройте соответствующую
ветку или PR и **Measures → Coverage**, затем проверьте покрытие Kotlin-файлов.
Входной JaCoCo XML объединяет оба набора тестов; метрика нового кода может отличаться
от общего покрытия приложения.

Используется существующий **Sonar way Quality Gate**: покрытие нового кода не ниже 80%,
контроль новых проблем качества, безопасности и дублирования. В GitHub PR откройте
**Checks** и результат SonarQube Cloud, затем перейдите по **Details** к анализу PR.
Если результат не появился, проверьте привязку проекта к репозиторию и доступ
GitHub-приложения SonarQube Cloud к этому репозиторию.
`sonar.qualitygate.wait=true` не включён: успешная отправка анализа не означает,
что сервер уже вычислил и одобрил Quality Gate. Required checks и сам Quality Gate
этой интеграцией не изменяются. Порог JaCoCo 80% для всего приложения продолжает
проверяться локально через `check`.

Подробнее: [workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)
и [методы анализа SonarQube Cloud](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/ci-based-analysis/overview-of-integrated-cis).

## CodeQL

Отдельный workflow [CodeQL](../.github/workflows/codeql.yaml), job `analyze`,
проверяет Java/Kotlin (`java-kotlin`) стандартным набором запросов CodeQL.
Он запускается для PR в `main`, при push в `main` и еженедельно по средам
в 04:23 UTC. Используется Advanced Setup; одновременно включать Default Setup
в настройках репозитория нельзя, поскольку он блокирует публикацию из этого workflow.

После инициализации `github/codeql-action/init@v4` с `build-mode: manual`
выполняется компиляция на Temurin JDK 25:

```sh
./gradlew --no-daemon --no-build-cache --rerun-tasks \
  -Pkotlin.compiler.execution.strategy=in-process \
  compileKotlin compileTestKotlin
```

Для извлечения Kotlin-кода CodeQL должен наблюдать реальную компиляцию исходников.
`--no-build-cache` и `--rerun-tasks` не позволяют заменить её готовыми результатами.
`--no-daemon` исключает использование уже работающего Gradle daemon,
а `in-process` запускает Kotlin-компилятор внутри отслеживаемого процесса Gradle.
Компилируются основной код и тестовые исходники; сами unit- и интеграционные тесты,
Testcontainers, JaCoCo и SonarScanner повторно не запускаются.

`github/codeql-action/analyze@v4` выполняет анализ и загружает результаты в GitHub
Code Scanning. Используется встроенный `GITHUB_TOKEN` с `contents: read` и
`security-events: write`; PAT и дополнительные secrets не нужны.
Обычный `pull_request` позволяет публиковать результаты для fork PR и Dependabot
с ограниченным токеном. Такие PR не пропускаются; для внешнего PR GitHub может
потребовать одобрение запуска workflow владельцем репозитория.

Результаты доступны в **GitHub → Security → Code scanning**. В PR откройте
**Checks → CodeQL / analyze → Details**, а также замечания Code scanning
в изменённых строках на вкладке **Files changed**. После первого запуска проверьте
в логах извлечение Kotlin-файлов и публикацию анализа `java-kotlin` в Code scanning.

Успешный job означает, что шаги анализа и загрузки завершились без ошибки;
он не означает отсутствия найденных уязвимостей. Проверяйте сами alerts и статус
обработки результатов в Code scanning. Ошибки анализа и публикации не подавляются.
CodeQL не является зависимостью CI, Tag, Deploy или SonarQube Cloud;
правила блокировки merge и существующие обязательные проверки не изменяются.

Подробнее: [анализ компилируемых языков](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages)
и [права при анализе Dependabot](https://docs.github.com/en/code-security/reference/code-scanning/troubleshoot-analysis-errors/resource-not-accessible).
Последний документ также описывает возможный `403` при push в default branch
после squash merge коммита, автором которого является Dependabot.
Workflow сохраняет эту ошибку видимой, без пропуска анализа или повышения прав.

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

Обе проверки работают в блокирующем режиме (`ignoreFailures = false`):
найденные нарушения и ошибки запуска приводят к неуспешному завершению сборки.
Проверки автоматически подключены к задаче `check`, поэтому
`./gradlew clean build` выполняет их вместе с обычными и интеграционными тестами.
Автоматическое форматирование в CI, baselines и подавления правил не настроены.
Для локального исправления форматирования можно запустить `./gradlew ktlintFormat`.
Отчёты ktlint находятся в `build/reports/ktlint/`, detekt — в `build/reports/detekt/`.

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
Объединённые HTML- и XML-отчёты JaCoCo публикуются в artifact `jacoco-coverage`.
Подробности выпуска приложения находятся в [Развёртывание](deployment.md).

[Kotlin Static Analysis](../.github/workflows/static-analysis.yaml) запускается
на pull request в `main` и push в `main`. Независимые jobs `ktlint` и `detekt`
используют Temurin JDK 25 и Gradle Wrapper; они выполняются параллельно
и не используют фильтрацию по изменённым файлам. Анализаторы также запускаются
в существующем job `build` через стандартную зависимость `check`.
Нарушения ktlint и detekt приводят к падению соответствующих jobs и job `build`.
