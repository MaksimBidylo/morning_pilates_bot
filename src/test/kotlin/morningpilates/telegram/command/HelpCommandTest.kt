package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USER_ID = 42L
private val EXPECTED_HELP =
    """
    <b>Помощь 🥋</b>

    Morning Pilates Bot — твой личный дневник тренировок по BJJ.
    Записывай, ищи и удаляй свои тренировки в личном чате с ботом.

    <b>Доступные команды</b>

    /profile — личный профиль спортсмена

    /training_add — записать тренировку

    /training_get_last — посмотреть последнюю тренировку

    /training_get_date — найти тренировки за дату

    /cancel — отменить текущее действие

    /cancel_all — отменить все незавершённые действия

    /start — приветствие

    /help — показать эту справку

    <b>Как заполнить профиль?</b>

    /profile покажет твой профиль с кнопкой «Редактировать», даже если тренировок ещё нет.
    Нажми её и выбери поле: остальные кнопки скроются на время ввода.
    Введи значение или выбери его кнопкой. «Очистить» удаляет значение, «Назад» возвращает к списку полей.
    После сохранения или очистки можно менять следующее поле в той же карточке.
    «Завершить редактирование» и повторный /profile возвращают к просмотру.
    Все поля необязательны. Значение можно очистить, ввод — отменить.
    Возраст и стаж BJJ рассчитываются автоматически.
    Для белого, синего, пурпура и коричневого пояса доступны «Страйпы» 0–4.
    Для чёрного пояса — «Дан» 0–10.
    Без пояса это поле скрыто. Смена цвета или очистка пояса сбрасывает отметки и дату получения.
    Обработанные сообщения удаляются по возможности; ошибки удаления не отменяют сохранение.
    Заверши редактирование профиля или действие дневника перед переходом к другому сценарию.

    <b>Как записать тренировку?</b>

    1. Введи /training_add и укажи дату и время.
    2. Добавь техники и их описания, если нужно.
    3. При желании оставь заметку.
    4. Подтверди сохранение.

    После сохранения бот покажет карточку тренировки.

    <b>Как найти тренировку?</b>

    /training_get_last покажет последнюю тренировку.
    /training_get_date запросит дату и покажет все тренировки за этот день.
    Листай карточки кнопками «Назад» и «Следующая».
    «Выбрать другую дату» вернёт к вводу даты.

    <b>Как удалить тренировку?</b>

    1. Открой просмотр через /training_get_last или /training_get_date.
    2. В нужной карточке нажми «Удалить тренировку».
    3. Проверь дату и время и нажми «Да, удалить».

    «Отмена» вернёт к карточке. Удаление нельзя отменить.
    В сообщении сразу после сохранения кнопки удаления нет.

    <b>Полезно знать</b>

    - Техники, описания и заметку можно пропустить.
    - /cancel закрывает профиль или просмотр. Если их нет — отменяет незавершённую тренировку.
    - Кнопка «Отменить тренировку» отменяет только создание.
    - /cancel_all сбрасывает все незавершённые действия.
    - Команды отмены не удаляют сохранённые записи и не откатывают уже начатое сохранение.
    - При перезапуске бота незавершённые действия теряются, сохранённые тренировки и профиль остаются.
    """.trimIndent()

class HelpCommandTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val userId = TelegramUserId(USER_ID)

    @ParameterizedTest
    @CsvSource("42, private", "-987654321, group")
    fun `repeated help sends the agreed text without buttons or creating a session`(
        chatId: Long,
        chatType: String,
    ) = runBlocking {
        createBot(chatId, chatType).use { bot ->
            val first = bot.sendCommandAndCollectReplies("/help").single()
            val second = bot.sendCommandAndCollectReplies("/help").single()

            assertEquals(SentTelegramMessage(chatId, EXPECTED_HELP), first)
            assertEquals(first, second)
            assertNotEquals(first.messageId, second.messageId)
            assertEquals(listOf("sendMessage", "sendMessage"), bot.apiCalls)
            assertTrue(bot.sendMessageRequests.all { it.parseMode == "HTML" && it.inlineKeyboard.isEmpty() })
            assertNull(storage.get(userId))
            assertNull(messages.get(userId))
            assertTrue(repository.saveCalls.isEmpty())
        }
    }

    @Test
    fun `repeated help preserves active training data and the existing question buttons remain usable`() =
        runBlocking {
            createBot().use { bot ->
                bot.sendCommand("/training_add")
                bot.sendTextAndCollectReplies("12.10.2026")
                bot.sendTextAndCollectReplies("6:30")
                val registered = assertNotNull(messages.get(userId))
                bot.pressInlineButtonAndCollectReplies(bot.currentMessage(registered.messageId), "Да")
                bot.sendTextAndCollectReplies("Armbar")
                bot.pressInlineButtonAndCollectReplies(bot.currentMessage(registered.messageId), "Да")
                bot.sendTextAndCollectReplies("Рычаг локтя из маунта")
                val session = assertNotNull(storage.get(userId))
                val state = assertIs<WaitingAddAnotherTechnique>(session.state)
                val question = bot.currentMessage(registered.messageId)
                val token = messages.questionToken(userId)
                val callback = bot.captureCallback(question, "Да", userId)
                val callCount = bot.apiCalls.size

                repeat(2) {
                    val help = bot.sendCommandAndCollectReplies("/help").single()

                    assertEquals(SentTelegramMessage(USER_ID, EXPECTED_HELP), help)
                    assertNotEquals(registered.messageId, TelegramMessageId(help.messageId))
                    assertEquals(session, storage.get(userId))
                    assertEquals(registered, messages.get(userId))
                    assertEquals(token, messages.questionToken(userId))
                    assertEquals(question, bot.currentMessage(registered.messageId))
                }
                assertEquals(listOf("sendMessage", "sendMessage"), bot.apiCalls.drop(callCount))
                assertTrue(repository.saveCalls.isEmpty())

                val interaction = bot.deliverCallbackAndCollectReplies(callback)

                assertTrue(interaction.acknowledged)
                assertTrue(interaction.replies.isEmpty())
                val continued = assertNotNull(storage.get(userId))
                assertEquals(session.sessionId, continued.sessionId)
                assertEquals(WaitingTechniqueName(state.details), continued.state)
                assertEquals(registered.messageId, assertNotNull(messages.get(userId)).messageId)
                assertEquals("🏷️ Введите название техники.", bot.currentMessage(registered.messageId).text)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private fun createBot(
        chatId: Long = USER_ID,
        chatType: String = "private",
    ): TestTelegramBot =
        TestTelegramBot(
            telegramBot =
                TelegramBot(
                    profileRepository = morningpilates.profile.FakeProfileRepository(),
                    TelegramProperties("test-token"),
                    storage,
                    FlowSessionManager(storage, listOf(TrainingFlowService(repository))),
                    messages,
                    TrainingQueryService(repository),
                    deleteTraining = repository::delete,
                ),
            chatId = chatId,
            chatType = chatType,
        )
}
