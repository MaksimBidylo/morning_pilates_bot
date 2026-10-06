# 🥋 Morning Pilates Bot

Telegram-бот для BJJ-группы, который помогает сохранять техники с тренировок и быстро находить их прямо в чате.

## 🎯 MVP

В первой версии бот будет поддерживать две основные возможности.

### 🤼 Сохранение техник

- сохранять описание техники может только администратор;
- добавление техники выполняется в личном чате с ботом;
- сохранённые техники можно запрашивать в групповом чате.

### 💬 Цитаты тренера

- участник группы вызывает бота командой;
- бот отправляет цитату тренера в чат.

## 👤 Роли

**Администратор**
- сохраняет техники через личный чат с ботом.

**Участник группы**
- запрашивает сохранённые техники;
- получает цитаты тренера.

### Стек

- Kotlin 2.4.20
- Ktor 3.6.0
- kotlinx.serialization 1.11.0
- [kotlin-telegram-bot 10.0.0](https://github.com/kotlin-telegram-bot/kotlin-telegram-bot/releases/tag/10.0.0)
- PostgreSQL 18, JDBC-драйвер 42.7.13
- jOOQ 3.21.9
- Flyway 12.11.0 (core и поддержка PostgreSQL)
- Logback 1.6.5 — логирование через SLF4J
- Gradle 9.3.0, Kotlin DSL, JDK 25 (последний LTS)

Версии плагинов и библиотек собраны в `gradle/libs.versions.toml`.

### Два окружения запуска

Локально приложение и PostgreSQL запускаются через Docker Compose.
В production приложение разворачивается на Render из того же `Dockerfile`,
а база создаётся отдельно как Render Postgres 18.

Приложение пока содержит только пустой модуль Ktor. Все HTTP-запросы возвращают
`404`. Обработчиков Telegram, подключения к БД и миграций пока нет.

### Локальный запуск через Docker Compose

Нужен Docker с поддержкой Compose. JDK и Gradle на компьютере для этого запуска
не требуются: приложение собирается внутри Docker на JDK 25.

```sh
touch src/main/resources/application-local.conf
docker compose up --build -d
docker compose logs -f app
docker compose down
```

Compose подключает `src/main/resources/application-local.conf` только для чтения.
Ktor загружает основной `application.conf` из образа, затем локальный файл:
совпадающие параметры из локального файла имеют приоритет. В нём можно указать,
например, `telegram.botToken`; если переопределения не нужны, файл можно оставить
пустым. Команда `touch` создаёт отсутствующий файл и сохраняет содержимое существующего.
Локальный конфиг исключён из Git и Docker-образа. Если файла нет, Compose выдаст
ошибку подключения. На Render используются основной конфиг и переменные окружения.

Приложение доступно на `http://localhost:8080`. PostgreSQL доступна с компьютера
по адресу `localhost:5432`; база, пользователь и пароль — `morning_pilates`.
Внутри контейнера приложения адрес базы — `postgres:5432`.
Compose передаёт будущие настройки подключения через `DB_JDBC_URL`, `DB_USER`
и `DB_PASSWORD`; пустое приложение пока их не читает.
Приложение запускается после успешной проверки готовности PostgreSQL.
Данные БД сохраняются в Docker volume `postgres_data` после `docker compose down`.

Если локальные порты заняты, их можно изменить:

```sh
APP_PORT=8081 POSTGRES_PORT=5433 docker compose up --build -d
```

Для запуска из IDEA или терминала можно отдельно установить JDK 25,
указать его в `JAVA_HOME` и использовать `./gradlew build` / `./gradlew run`.

### Логирование

Logback пишет логи в stdout: они видны в `docker compose logs -f app`
и в логах сервиса на Render. Конфигурация — `src/main/resources/logback.xml`.
По умолчанию используется уровень `INFO`; его можно изменить переменной
окружения `LOG_LEVEL`, например на `DEBUG`. В Compose такую переменную нужно
передать через `environment` сервиса `app`, на Render — через Environment.
Время в логах выводится в UTC. Логирование HTTP-запросов пока не подключено.

### Развёртывание на Render

Для текущего каркаса создайте Web Service из репозитория, выберите нужную ветку,
Language: Docker и Dockerfile Path: `./Dockerfile`. Команда запуска уже указана
в Dockerfile. Render собирает образ и запускает контейнер приложения.
Ktor слушает `0.0.0.0` и порт из переменной `PORT`, которую задаёт Render.

Для БД создайте Render Postgres 18 в том же регионе, что и приложение.
Будущие параметры подключения задаются в Environment сервиса:

- `DB_JDBC_URL` — `jdbc:postgresql://<internal-host>:5432/<database>`
  с внутренним адресом базы из Render.
- `DB_USER` и `DB_PASSWORD` — учётные данные Render Postgres.
- `TELEGRAM_BOT_TOKEN` — токен Telegram.

Документация:
[Docker](https://render.com/docs/docker),
[Web Services](https://render.com/docs/web-services),
[Background Workers](https://render.com/docs/background-workers),
[Postgres](https://render.com/docs/postgresql-creating-connecting).
