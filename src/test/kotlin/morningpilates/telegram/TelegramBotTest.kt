package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val START_COMMAND = "/start"
private const val TRAINING_ADD_COMMAND = "/training_add"
private const val SENDER_ID = 42L
private const val GROUP_CHAT_ID = -987654321L
private const val TEST_TOKEN = "test-token"
private const val EXPECTED_GREETING = "Morning Pilates Bot работает 🥋"
private const val EXPECTED_UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."
private const val START_TIME_INPUT = "6:30"
private const val YES_BUTTON_TEXT = "Да"
private const val NO_BUTTON_TEXT = "Нет"
private const val EXPECTED_TECHNIQUE_NAME_PROMPT = "Введите название техники."
private const val EXPECTED_ADD_NOTE_PROMPT = "Добавить заметку?"
private val TRAINING_DATE = LocalDate.of(2026, 10, 12)
private val EXPECTED_DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()

class TelegramBotTest {
    @Test
    fun `registered no callback advances to add note and acknowledges button press`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val originalSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            val details = TrainingDetails(date = TRAINING_DATE, time = LocalTime.of(6, 30))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, NO_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingAddNote(details)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            SENDER_ID,
                            EXPECTED_ADD_NOTE_PROMPT,
                            inlineButtonTexts = listOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT),
                        ),
                    ),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `registered yes callback advances to technique name and acknowledges button press`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val originalSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            val details = TrainingDetails(date = TRAINING_DATE, time = LocalTime.of(6, 30))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingTechniqueName(details)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TECHNIQUE_NAME_PROMPT)),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `registered start command sends greeting without starting training`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            createTestBot(storage).use { bot ->
                val reply = bot.sendCommand(START_COMMAND, senderId = SENDER_ID)

                assertEquals(SentTelegramMessage(SENDER_ID, EXPECTED_GREETING), reply)
                assertNull(storage.get(TelegramUserId(SENDER_ID)))
            }
        }

    @Test
    fun `registered training add starts session in supplied storage and requests date`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            createTestBot(storage).use { bot ->
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = SENDER_ID)

                assertEquals(WaitingDate, storage.get(TelegramUserId(SENDER_ID))?.state)
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_DATE_PROMPT)), replies)
            }
        }

    @Test
    fun `registered commands preserve active training and report repeated training add`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val expectedSession = startSessionAwaitingConfirmation(storage, telegramUserId)
            createTestBot(storage).use { bot ->
                val replies =
                    listOf(START_COMMAND, TRAINING_ADD_COMMAND, START_COMMAND).flatMap { command ->
                        bot.sendCommandAndCollectReplies(command, senderId = SENDER_ID)
                    }

                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(
                    listOf(EXPECTED_GREETING, EXPECTED_UNFINISHED_TRAINING_MESSAGE, EXPECTED_GREETING),
                    replies.map { it.text },
                )
            }
        }

    @ParameterizedTest(name = "registered training add in {0} is ignored")
    @CsvSource(
        "group, $GROUP_CHAT_ID",
        "supergroup, -100987654321",
    )
    fun `registered training add ignores group chats`(
        chatType: String,
        chatId: Long,
    ) = runBlocking {
        val storage: FlowStorage = InMemoryFlowStorage()
        createTestBot(storage, chatId = chatId, chatType = chatType).use { bot ->
            val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = SENDER_ID)

            assertNull(storage.get(TelegramUserId(SENDER_ID)))
            assertEquals(emptyList(), replies)
        }
    }

    private fun createTestBot(
        storage: FlowStorage,
        chatId: Long = SENDER_ID,
        chatType: String = "private",
        repository: FakeTrainingRepository = FakeTrainingRepository(),
    ): TestTelegramBot {
        val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
        return TestTelegramBot(
            telegramBot =
                TelegramBot(
                    properties = TelegramProperties(TEST_TOKEN),
                    flowStorage = storage,
                    sessionManager = sessionManager,
                ),
            chatId = chatId,
            chatType = chatType,
        )
    }
}
