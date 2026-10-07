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
- Локальный конфиг, подключаемый в контейнер только на время запуска.
- Логирование в stdout, удалённая отладка JVM и GitHub Actions для сборки.

PostgreSQL запускается с базой `morning_pilates`, но приложение пока к ней
не подключается. Схема данных, миграции Flyway и запросы jOOQ ещё не реализованы.
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
- jOOQ 3.21.9.
- Flyway 12.11.0 с поддержкой PostgreSQL.
- Logback 1.6.5, SLF4J.
- Gradle 9.3.0, Kotlin DSL.

Версии библиотек и плагинов находятся в `gradle/libs.versions.toml`.

## Структура приложения

```text
src/main/kotlin/morningpilates/
├── Application.kt                 # Настройка Ktor и жизненного цикла бота
└── telegram/
    ├── TelegramBot.kt             # Создание бота, запуск и остановка polling
    ├── TelegramProperties.kt      # Настройки секции telegram
    └── command/
        └── StartCommand.kt        # Регистрация обработчика /start
```

Основная конфигурация — `src/main/resources/application.conf`.
Параметр `telegram.botToken` читается из `TELEGRAM_BOT_TOKEN` либо локального конфига.
Токен обязателен для запуска приложения.

## Локальный запуск через Docker Compose

Нужен Docker с поддержкой Compose. JDK и Gradle на компьютере для этого способа
не требуются: приложение собирается внутри Docker.

1. Создайте локальный файл:

   ```sh
   touch src/main/resources/application-local.conf
   ```

2. Укажите в нём токен своего бота, полученный у BotFather:

   ```hocon
   telegram {
       botToken = "YOUR_TELEGRAM_BOT_TOKEN"
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
Compose передаёт `DB_JDBC_URL`, `DB_USER` и `DB_PASSWORD`, но приложение пока их не читает.

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
Собрать проект:

```sh
./gradlew build
```

Запустить с основным и локальным конфигами из корня проекта:

```sh
./gradlew run --args='-config=application.conf -config=src/main/resources/application-local.conf'
```

Либо задайте `TELEGRAM_BOT_TOKEN` в окружении процесса и запустите `./gradlew run`.
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
Тесты работают без обращения к Telegram и БД.
Запуск тестов: `./gradlew test`.

## Развёртывание на Render

Для production используется тот же `Dockerfile`: сборка выполняется на JDK 25,
а приложение работает на JRE 25 от пользователя без root. Compose и локальный
конфиг для этого запуска не используются.

Создайте **Web Service** из репозитория, выберите ветку, **Language: Docker**
и **Dockerfile Path: `./Dockerfile`**. Команда запуска уже указана в образе.
В Environment сервиса задайте `TELEGRAM_BOT_TOKEN`.

Ktor слушает `0.0.0.0` и порт из переменной `PORT`, которую задаёт Render.
Для HTTP-проверки готовности укажите **Health Check Path: `/health`**.

После реализации доступа к БД потребуется отдельная Render Postgres в том же регионе
и переменные окружения приложения:

- `DB_JDBC_URL` — `jdbc:postgresql://<internal-host>:5432/<database>`;
- `DB_USER` и `DB_PASSWORD` — учётные данные Render Postgres.

Документация: [Docker](https://render.com/docs/docker),
[Web Services](https://render.com/docs/web-services),
[Health Checks](https://render.com/docs/health-checks),
[Postgres](https://render.com/docs/postgresql-creating-connecting).
