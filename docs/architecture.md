# Архитектура приложения

[README](../README.md) · [Разработка](development.md) · [Развёртывание](deployment.md)

Morning Pilates Bot — персональный Telegram-дневник тренировок по Brazilian Jiu-Jitsu (BJJ).
Каждый пользователь ведёт собственный дневник: дата и время тренировки,
изученные техники с необязательными описаниями и необязательная заметка о тренировке.
Просмотр истории собственных тренировок планируется на последующем этапе.

## Структура приложения

```text
src/main/kotlin/morningpilates/
├── Application.kt
├── bootstrap/
│   ├── ApplicationConfiguration.kt
│   ├── ApplicationLifecycle.kt
│   └── TrainingFlowConfiguration.kt
├── config/
│   ├── database/
│   │   └── DatabaseProperties.kt
│   ├── security/
│   │   ├── TelegramIdKeys.kt
│   │   └── TelegramIdProperties.kt
│   └── telegram/
│       └── TelegramProperties.kt
├── telegram/
│   ├── BotLifecycle.kt
│   ├── TelegramBot.kt
│   └── command/
│       └── StartCommand.kt
├── health/
│   └── HealthRoutes.kt
├── flow/
│   ├── FlowHandler.kt
│   ├── model/
│   │   ├── FlowState.kt
│   │   ├── AwaitingContinuation.kt
│   │   ├── FlowEvent.kt
│   │   ├── FlowResult.kt
│   │   ├── SessionId.kt
│   │   └── UserSession.kt
│   └── session/
│       ├── FlowStorage.kt
│       └── FlowSessionManager.kt
├── training/
│   ├── Technique.kt
│   ├── TechniqueService.kt
│   ├── TechniqueRepository.kt
│   ├── TrainingDetails.kt
│   ├── TrainingId.kt
│   ├── TrainingFlowState.kt
│   ├── TrainingFlowService.kt
│   └── TrainingRepository.kt
├── user/
│   ├── TelegramUserId.kt
│   └── UserRepository.kt
└── infrastructure/
    ├── InMemoryFlowStorage.kt
    ├── persistence/
    │   ├── Database.kt
    │   ├── PostgresDatabase.kt
    │   ├── PostgresTrainingRepository.kt
    │   ├── PostgresUserRepository.kt
    │   └── jooq/
    └── security/
        ├── TelegramIdProtection.kt
        └── TelegramIdProtector.kt
```

`bootstrap` собирает компоненты приложения и управляет их жизненным циклом.
`ApplicationConfiguration` задаёт порядок инициализации, `ApplicationLifecycle` —
запуск и освобождение ресурсов, а `TrainingFlowConfiguration` создаёт
`PostgresTrainingRepository`, `TrainingFlowService` и `FlowSessionManager`.
`config.database`, `config.security` и `config.telegram` содержат типизированные
настройки соответствующих компонентов.
`telegram` отвечает за взаимодействие с Telegram,
`health` — HTTP-проверку состояния. `flow.model` задаёт общие модели состояний,
событий, результатов, пользовательской сессии и её идентификатора.
`flow.session` содержит контракт хранилища и менеджер сессий. Общий контракт обработчика
`FlowHandler` находится непосредственно в `flow`.
`training` содержит состояния и обработчик добавления тренировки `TrainingFlowService`,
контракт сохранения `TrainingRepository`, модели `TrainingDetails` и `TechniqueDraft`
в файле `TrainingDetails.kt`, а также пустые каркасы `Technique`, `TechniqueService`
и `TechniqueRepository`.
`user` содержит тип `TelegramUserId` и контракт идентификации пользователя
`UserRepository`.

В `infrastructure` находится реализация хранилища сессий в памяти.
В `infrastructure.persistence` находятся пул БД, Flyway и PostgreSQL-реализации
репозиториев. Они выполняют параметризованные SQL-запросы через jOOQ `DSLContext`
без генерации jOOQ-классов. Каталог `jooq` пока пуст и сохранён через `.gitkeep`.
`infrastructure.security` содержит защиту Telegram ID и доступ к её экземпляру
из контекста Ktor.

## Границы зависимостей

`training` использует модели из `flow.model`, контракт `FlowHandler` из `flow`
и тип `TelegramUserId` из `user`.
Менеджер сессий зависит от `FlowHandler` и `FlowStorage`,
не обращаясь к конкретным состояниям или сервисам
тренировки. `InMemoryFlowStorage` реализует контракт `FlowStorage` в пакете
`infrastructure`. Контракты `TrainingRepository` и `UserRepository` объявлены
в `training` и `user`, а их PostgreSQL-реализации — в `infrastructure.persistence`.
Контракты не зависят от PostgreSQL, jOOQ или Telegram API.

[Архитектурные тесты](../src/test/kotlin/morningpilates/architecture/ArchitectureTest.kt)
запрещают зависимости `training` от `telegram` и `infrastructure`,
а также циклы между основными пакетами приложения.

## Запуск и остановка приложения

[Application.kt](../src/main/kotlin/morningpilates/Application.kt) содержит точку
входа и модуль Ktor, который вызывает
[ApplicationConfiguration.kt](../src/main/kotlin/morningpilates/bootstrap/ApplicationConfiguration.kt).
Конфигурация приложения сначала читает `TelegramIdProperties` из
`security.telegramId` и декодирует
Base64-значения двух независимых ключей. После проверки длины каждого ключа
в 32 байта и различия их содержимого создаётся `TelegramIdProtector`.
Экземпляр сохраняется в атрибутах Ktor и доступен через
`Application.telegramIdProtector`. Ошибка конфигурации ключей прерывает запуск
до подключения к БД и создания бота.

После успешной инициализации защиты приложение читает типизированные настройки
Telegram и БД и подключается к PostgreSQL. Подключение доступно через
`Application.database`.
[PostgresDatabase](../src/main/kotlin/morningpilates/infrastructure/persistence/PostgresDatabase.kt)
создаёт пул HikariCP и выполняет Flyway до запуска Telegram polling.
Ошибка подключения или миграции прерывает запуск до polling.
При ошибке миграции созданный пул закрывается.

Flyway хранит историю в `flyway_schema_history` и читает миграции из
[`src/main/resources/db/migration`](../src/main/resources/db/migration).
`V1__create_training_journal.sql` создаёт таблицы `users`, `trainings`
и `training_techniques`. `V2__add_user_admin_flag.sql` добавляет в `users`
поле `is_admin BOOLEAN NOT NULL DEFAULT FALSE`: новые и существовавшие до
миграции пользователи получают значение `false`.

Жизненным циклом ресурсов управляет
[ApplicationLifecycle.kt](../src/main/kotlin/morningpilates/bootstrap/ApplicationLifecycle.kt).
Бот запускается вместе с Ktor. При остановке приложения polling останавливается,
а пул БД закрывается. Если создание или запуск бота завершается ошибкой,
приложение также пытается остановить бот и закрыть БД перед передачей ошибки выше.

[TelegramBot](../src/main/kotlin/morningpilates/telegram/TelegramBot.kt)
использует long polling и пока регистрирует только команду `/start`.

Команда создания тренировки `/training_add` находится в разработке.
Согласованный сценарий: запуск в личном чате создаёт новую пользовательскую
сессию и запускает Training Flow; вопросы с выбором ответа используют
inline-кнопки «Да/Нет». Менеджер сессий, сценарий тренировки и сохранение через
PostgreSQL-репозитории уже реализованы, но их подключение к Telegram-команде
ещё не выполнено.

[GET /health](../src/main/kotlin/morningpilates/health/HealthRoutes.kt)
возвращает `200 OK` и текст `OK`. Этот маршрут подтверждает работу HTTP-сервера;
подключение к БД и Telegram он не проверяет. Корневой маршрут `/` пока возвращает `404`.

Настройки подключения, переменные окружения и способы локального запуска описаны
в [руководстве по разработке](development.md).

## Общие контракты сценариев

[FlowState](../src/main/kotlin/morningpilates/flow/model/FlowState.kt) — общий интерфейс
состояний. [FlowHandler](../src/main/kotlin/morningpilates/flow/FlowHandler.kt)
определяет, поддерживает ли обработчик состояние через
`supports(state: FlowState): Boolean`, и обрабатывает пару состояния и события
через `handle(state: FlowState, event: FlowEvent): FlowResult`.

[FlowEvent](../src/main/kotlin/morningpilates/flow/model/FlowEvent.kt) имеет два варианта:

- `UserInput(telegramUserId, text)` передаёт идентификатор пользователя
  `TelegramUserId` и пользовательский текст.
- `Continue(telegramUserId, sessionId)` обозначает внутреннее продолжение без нового
  сообщения пользователя и передаёт идентификаторы пользователя и текущей сессии.

[FlowResult](../src/main/kotlin/morningpilates/flow/model/FlowResult.kt) описывает результат
обработки события:

- `Transition(nextState)` запрашивает переход в новое состояние.
- `Rejected(reason)` отклоняет событие с причиной.
- `Completed` завершает сценарий успешно.
- `Cancelled` завершает сценарий отменой.

[AwaitingContinuation](../src/main/kotlin/morningpilates/flow/model/AwaitingContinuation.kt)
— маркер состояния, после перехода в которое менеджер должен передать `Continue`.
`Completed` и `Cancelled` являются результатами обработки, а не состояниями сессии.

## Хранение пользовательских сессий

[UserSession](../src/main/kotlin/morningpilates/flow/model/UserSession.kt) содержит состояние
`FlowState`, время создания `createdAt: Instant` и `sessionId: SessionId`.
`SessionId` оборачивает UUID, по умолчанию создаваемый через `SessionId.new()`.
[FlowStorage](../src/main/kotlin/morningpilates/flow/session/FlowStorage.kt) хранит не более
одной незавершённой сессии для каждого `userId: TelegramUserId`. Этот тип
оборачивает `Long` и скрывает исходное значение в `toString()`. Реализация хранилища
[InMemoryFlowStorage](../src/main/kotlin/morningpilates/infrastructure/InMemoryFlowStorage.kt)
использует `ConcurrentHashMap`:

| Метод | Поведение |
|---|---|
| `get(userId)` | Возвращает текущую сессию или `null`, если её нет. |
| `start(userId, session)` | Атомарно создаёт сессию, если записи ещё нет; возвращает `true`. При существующей сессии возвращает `false` и сохраняет её. |
| `update(userId, expected, updated)` | Атомарно заменяет сессию только при совпадении с `expected`. Возвращает `false` при отсутствии записи или несовпадении. |
| `remove(userId, expected)` | Атомарно удаляет сессию только при совпадении с `expected`. Возвращает `false` при отсутствии записи или несовпадении. |

Условные операции выполняют сравнение и замену или удаление атомарно
(compare-and-set, CAS). Сравнение с `expected` использует равенство `UserSession`,
включая `state`, `createdAt` и `sessionId`. Это предотвращает обновление или удаление,
если текущая сессия уже отличается от `expected`; отдельного номера версии у сессии нет.
Сессии хранятся в памяти одного экземпляра приложения и теряются при его перезапуске.

## Жизненный цикл сессии

[FlowSessionManager](../src/main/kotlin/morningpilates/flow/session/FlowSessionManager.kt)
обрабатывает существующие сессии. Он не создаёт их: вызывающий код должен передать
начальное состояние и `createdAt` в `UserSession` и вызвать `FlowStorage.start`.
Идентификатор новой сессии создаётся по умолчанию.
Создание сессии через Telegram-команду `/training_add` пока не подключено.

Метод `handle(input: FlowEvent.UserInput)` читает активную сессию по
`input.telegramUserId` и выбирает единственный обработчик, для которого
`supports` вернул `true`.
При отсутствии сессии или обработчика он возвращает `Rejected`.
Несколько подходящих обработчиков приводят к `IllegalStateException`
до вызова их `handle`. Результат обработчика применяется к хранилищу:

| `FlowResult` | Действие менеджера |
|---|---|
| `Transition(nextState)` | Условный `update(userId, expected, updated)` с новым состоянием и прежними `createdAt` и `sessionId`. |
| `Rejected(reason)` | Возвращается без изменения сессии. |
| `Completed` / `Cancelled` | Условный `remove(userId, expected)` текущей сессии. |

Если условный `update` или `remove` вернул `false`, менеджер возвращает
`Rejected("Состояние сессии изменилось")` без повторной операции и отката.
После успешного перехода в состояние с маркером `AwaitingContinuation` менеджер
заново выбирает обработчик по новому состоянию и передаёт ему
`Continue(telegramUserId, sessionId)` с идентификатором текущей сессии.
Результат продолжения применяется по тем же правилам и возвращается вызывающему коду.

За один вызов `handle` менеджер выполняет максимум одно внутреннее продолжение.
Он не запускает цикл продолжений, даже если результат `Continue` снова переводит
сессию в состояние с маркером `AwaitingContinuation`.

Для тренировки это означает, что `Saving` сначала закрепляется условным обновлением
сессии, и только затем `Continue` запускает сохранение.
При проигранном обновлении сохранение не запускается.
При успешном сохранении менеджер условно удаляет сессию `Saving`.
При `Result.failure` сервис возвращает `Transition(WaitingConfirmation)`;
менеджер условно заменяет сессию `Saving` на `WaitingConfirmation`,
сохраняя данные тренировки, `createdAt` и `sessionId`.

## Состояния и данные тренировки

[TrainingFlowState](../src/main/kotlin/morningpilates/training/TrainingFlowState.kt)
— `sealed interface`, наследующий `FlowState`.
Модель использует Type-State Pattern: каждый тип состояния определяет,
какие данные обязательны на этом этапе.

| Состояние | Обязательные данные |
|---|---|
| `WaitingDate` | Данных нет. |
| `WaitingStartTime` | `date: LocalDate`. |
| `WaitingAddTechnique` | `details: TrainingDetails`. |
| `WaitingTechniqueName` | `details: TrainingDetails`. |
| `WaitingAddTechniqueDescription` | `details: TrainingDetails`, `techniqueName: String`. |
| `WaitingTechniqueDescription` | `details: TrainingDetails`, `techniqueName: String`. |
| `WaitingAddAnotherTechnique` | `details: TrainingDetails`. |
| `WaitingAddNote` | `details: TrainingDetails`. |
| `WaitingNote` | `details: TrainingDetails`. |
| `WaitingConfirmation` | `details: TrainingDetails`. |
| `Saving` | `details: TrainingDetails`; реализует маркер `AwaitingContinuation`. |

В [TrainingDetails](../src/main/kotlin/morningpilates/training/TrainingDetails.kt)
обязательны `date: LocalDate` и `time: LocalTime`.
`techniques: List<TechniqueDraft>` по умолчанию пуст, `note: String?` — `null`.
У `TechniqueDraft` обязательное поле `name: String` и необязательное
`description: String?`, по умолчанию `null`.

Идентификаторы передаются отдельно от `TrainingDetails`.
`TrainingFlowService` получает `TelegramUserId` из события, а `TrainingId` — через
[`SessionId.toTrainingId()`](../src/main/kotlin/morningpilates/training/TrainingId.kt)
с сохранением UUID. Расширение объявлено в пакете `training`; пакет `flow`
от него не зависит. Повторная попытка сохранения той же сессии использует
тот же `TrainingId`.

## Переходы сценария тренировки

[TrainingFlowService](../src/main/kotlin/morningpilates/training/TrainingFlowService.kt)
реализует `FlowHandler` для всех `TrainingFlowState`.
Прямой вызов `handle` с состоянием другого сценария приводит к
`IllegalArgumentException` до обработки события.
В строках таблицы с текстовым вводом используется `FlowEvent.UserInput(telegramUserId, text)`;
переход в новое состояние возвращается как `FlowResult.Transition`.

| Состояние | Ввод или событие | Результат |
|---|---|---|
| `WaitingDate` | Корректная дата, например `2026-10-09`. | `WaitingStartTime` с введённой датой. |
| `WaitingStartTime` | Корректное время, например `19:30`. | `WaitingAddTechnique` с датой, временем, пустым списком техник и `note = null`. |
| `WaitingAddTechnique` | «Да» / «Нет». | `WaitingTechniqueName` / `WaitingAddNote`. |
| `WaitingTechniqueName` | Непустое название техники. | `WaitingAddTechniqueDescription`; техника ещё не добавлена в список. |
| `WaitingAddTechniqueDescription` | «Да» / «Нет». | `WaitingTechniqueDescription` / добавление техники без описания и `WaitingAddAnotherTechnique`. |
| `WaitingTechniqueDescription` | Непустой текст описания. | Добавление техники с описанием и `WaitingAddAnotherTechnique`. |
| `WaitingAddAnotherTechnique` | «Да» / «Нет». | `WaitingTechniqueName` / `WaitingAddNote`. |
| `WaitingAddNote` | «Да» / «Нет». | `WaitingNote` / `WaitingConfirmation`. |
| `WaitingNote` | Непустой текст заметки. | `WaitingConfirmation` с заполненным `note`. |
| `WaitingConfirmation` | «Да» / «Нет». | `Saving` / `FlowResult.Cancelled`. |
| `Saving` | `FlowEvent.Continue(telegramUserId, sessionId)`. | Один вызов `TrainingRepository.save(telegramUserId, trainingId, details)`: `Result.success` даёт `FlowResult.Completed`, `Result.failure` — переход в `WaitingConfirmation` с теми же данными. |

Дата принимается в порядке `ДД-ММ-ГГГГ` или `ГГГГ-ММ-ДД` с разделителями
`.`, `/` или `-`. В одной дате разделители должны совпадать. День и месяц
содержат строго две цифры, год — четыре. Например, `11.10.2026`, `11/10/2026`,
`11-10-2026`, `2026.10.11`, `2026/10/11` и `2026-10-11` дают один и тот же
`LocalDate`. Американский порядок `ММ/ДД/ГГГГ` не поддерживается.
Парсинг через `java.time` строго проверяет календарную дату, включая високосные
годы, и не исправляет некорректные значения автоматически.

Время принимается в формате `Ч:ММ` или `ЧЧ:ММ`, также разрешён дефис вместо
двоеточия: `8:00`, `08:00`, `8-00`, `08-00`, `19:30` и `19-30`.
Часы содержат одну или две цифры, минуты — строго две; допустимый диапазон —
`00:00`–`23:59`. Секунды и дробная часть не поддерживаются.
Эквивалентные записи дают одинаковый `LocalTime`.
Пробелы по краям даты и времени игнорируются. Некорректный ввод возвращает
`FlowResult.Rejected` с причиной ошибки; переход в следующее состояние не происходит.

Выбор «Да» или «Нет» требует полного совпадения слова после удаления крайних
пробелов и приведения к нижнему регистру. Название, описание и заметка также
обрезаются по краям; пустой текст отклоняется. Внутренние пробелы и переносы строк
сохраняются. Повторяющиеся названия допустимы; каждая техника добавляется в конец
нового списка, исходные данные не изменяются. При вводе заметки сервис создаёт
копию `TrainingDetails` с новым `note`, сохраняя дату, время и список техник.
Пользовательский ввод в `Saving` отклоняется; `Continue` вне `Saving` также отклоняется.

### Сообщения при вводе даты и времени

Запрос даты:

> Введите дату тренировки.
>
> Например: 11.10.2026 или 2026-10-11.
>
> Можно использовать разделители: точку, дефис или косую черту.

Ошибка даты, возвращаемая в `FlowResult.Rejected.reason`:

> Некорректная дата. Используйте формат ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. Разделители: ., / или -. Проверьте правильность даты.

Запрос времени:

> Введите время начала тренировки.
>
> Например: 8:00, 19:30 или 8-00.

Ошибка времени, возвращаемая в `FlowResult.Rejected.reason`:

> Некорректное время. Используйте формат Ч:ММ, ЧЧ:ММ или аналогичный формат с дефисом. Например: 8:00 или 19-30.

Начальные запросы выше согласованы, но пока не отправляются пользователю:
Training Flow ещё не подключён к Telegram. Подключение подсказок и отображение
результатов Flow в Telegram относятся к отдельному этапу интеграции.
Новый инфраструктурный слой или API для хранения этих строк в текущей задаче
не вводится.

## Сохранение тренировки и текущие ограничения

[TrainingRepository](../src/main/kotlin/morningpilates/training/TrainingRepository.kt)
определяет контракт:

```kotlin
fun save(
    telegramUserId: TelegramUserId,
    trainingId: TrainingId,
    details: TrainingDetails,
): Result<Unit>
```

Сервис делает один вызов `save` на событие `Continue` в состоянии `Saving`
и не повторяет его автоматически.
`Result.success` завершает сценарий, а возвращённый `Result.failure` переводит
его обратно к подтверждению. Исключение, выброшенное самим репозиторием,
сервис не перехватывает.

[PostgresTrainingRepository](../src/main/kotlin/morningpilates/infrastructure/persistence/PostgresTrainingRepository.kt)
в одной транзакции получает или создаёт пользователя, сохраняет тренировку
с переданным `TrainingId` и добавляет её техники. Позиции техник начинаются
с единицы и соответствуют порядку списка `TrainingDetails.techniques`.
Ошибка на любом шаге откатывает все изменения этой транзакции, включая создание
нового пользователя, и возвращается как `Result.failure`.

Сохранение идемпотентно по `TrainingId`: повторный вызов с тем же пользователем
и теми же данными, включая порядок техник, завершается успешно без дубликатов.
Если идентификатор уже занят тренировкой другого пользователя или с другими
данными, репозиторий возвращает `Result.failure` и не изменяет сохранённую запись.

[PostgresUserRepository](../src/main/kotlin/morningpilates/infrastructure/persistence/PostgresUserRepository.kt)
реализует `UserRepository.getOrCreate(telegramUserId: TelegramUserId): UUID`.
UUID нового пользователя генерируется приложением. Повторные и конкурентные
вызовы для одного Telegram ID возвращают UUID одной записи; существующий
`is_admin` не изменяется. Репозиторий использует переданный `DSLContext`,
не открывает собственную транзакцию и передаёт ошибки БД вызывающему коду.
Поэтому при сохранении тренировки он участвует в транзакции
`PostgresTrainingRepository`.

Telegram ID хранится только в защищённом виде: `telegram_id_encrypted` содержит
результат AES-256-GCM со случайным nonce, а поиск выполняется по HMAC-SHA256
в `telegram_id_lookup`. Криптографические операции выполняет существующий
`TelegramIdProtector` с независимыми ключами шифрования и поиска.

Условные операции хранилища сессий в памяти и запись тренировки в PostgreSQL
не объединены общей транзакцией. Идемпотентность репозитория действует для
повторного использования того же `TrainingId`; сессии и их идентификаторы
не восстанавливаются после перезапуска приложения.

В тестах используется
[FakeTrainingRepository](../src/test/kotlin/morningpilates/training/FakeTrainingRepository.kt),
который записывает независимые снимки переданных данных и возвращает заданный результат.
Создание репозиториев и подключение менеджера сессий и сценария тренировки
к командам Telegram пока не реализованы в `Application`.
