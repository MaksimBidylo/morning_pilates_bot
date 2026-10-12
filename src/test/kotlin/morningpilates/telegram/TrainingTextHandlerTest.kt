package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.flow.FlowSessionManager
import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.command.registerTrainingAddCommand
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENDER_ID = 42L
private const val GROUP_CHAT_ID = -987654321L
private const val SUPERGROUP_CHAT_ID = -100987654321L
private const val START_COMMAND = "/start"
private const val TRAINING_ADD_COMMAND = "/training_add"
private const val EXPECTED_GREETING = "Morning Pilates Bot работает 🥋"
private const val EXPECTED_UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."
private const val VALID_DATE_INPUT = "11/10/2026"
private const val INVALID_DATE_INPUT = "31.02.2026"
private const val EXPECTED_INVALID_DATE_MESSAGE =
    "Некорректная дата. Используйте формат ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. " +
        "Разделители: ., / или -. Проверьте правильность даты."
private val EXPECTED_TRAINING_DATE = LocalDate.of(2026, 10, 11)
private val EXPECTED_TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

class TrainingTextHandlerTest {
    private val storage: FlowStorage = InMemoryFlowStorage()
    private val telegramUserId = TelegramUserId(SENDER_ID)
    private val repository = FakeTrainingRepository()
    private val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @Test
    fun `valid training date advances session and requests start time without saving`() =
        runBlocking {
            // Given
            val originalSession = startSessionAwaitingDate()
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(
                    originalSession.copy(state = WaitingStartTime(EXPECTED_TRAINING_DATE)),
                    storage.get(telegramUserId),
                )
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TIME_PROMPT)), replies)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `invalid training date preserves session and allows retry after an error message`() =
        runBlocking {
            // Given
            val originalSession = startSessionAwaitingDate()
            createTestBot().use { bot ->
                // When
                val errorReplies = bot.sendTextAndCollectReplies(INVALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_INVALID_DATE_MESSAGE)), errorReplies)

                // When
                val retryReplies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(
                    originalSession.copy(state = WaitingStartTime(EXPECTED_TRAINING_DATE)),
                    storage.get(telegramUserId),
                )
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TIME_PROMPT)), retryReplies)
            }
        }

    @ParameterizedTest(name = "training text in {0} is ignored")
    @CsvSource(
        "group, $GROUP_CHAT_ID",
        "supergroup, $SUPERGROUP_CHAT_ID",
    )
    fun `training text in group preserves private session and sends no reply`(
        chatType: String,
        chatId: Long,
    ) = runBlocking {
        // Given
        val originalSession = startSessionAwaitingDate()
        createTestBot(chatId = chatId, chatType = chatType).use { bot ->
            // When
            val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

            // Then
            assertEquals(originalSession, storage.get(telegramUserId))
            assertEquals(emptyList(), replies)
        }
    }

    @Test
    fun `training text without sender preserves existing session and sends no reply`() =
        runBlocking {
            // Given
            val originalSession = startSessionAwaitingDate()
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = null)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertEquals(emptyList(), replies)
            }
        }

    @Test
    fun `training text without active session does not create session or send reply`() =
        runBlocking {
            // Given
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertNull(storage.get(telegramUserId))
                assertEquals(emptyList(), replies)
            }
        }

    @ParameterizedTest(name = "{0} is handled only as a command")
    @CsvSource(
        "$START_COMMAND, '$EXPECTED_GREETING'",
        "$TRAINING_ADD_COMMAND, '$EXPECTED_UNFINISHED_TRAINING_MESSAGE'",
    )
    fun `commands preserve active session and send only their own reply`(
        command: String,
        expectedReply: String,
    ) = runBlocking {
        // Given
        val originalSession = startSessionAwaitingDate()
        createTestBot().use { bot ->
            // When
            val replies = bot.sendCommandAndCollectReplies(command, senderId = SENDER_ID)

            // Then
            assertEquals(originalSession, storage.get(telegramUserId))
            assertEquals(listOf(SentTelegramMessage(SENDER_ID, expectedReply)), replies)
        }
    }

    private fun startSessionAwaitingDate(): UserSession {
        val session = UserSession(state = WaitingDate, createdAt = Instant.EPOCH)
        check(storage.start(telegramUserId, session))
        return session
    }

    private fun createTestBot(
        chatId: Long = SENDER_ID,
        chatType: String = "private",
    ) = TestTelegramBot(
        chatId = chatId,
        chatType = chatType,
        registerCommands = {
            registerStartCommand()
            registerTrainingAddCommand(storage)
            registerTrainingTextHandler(storage, sessionManager)
        },
    )
}
