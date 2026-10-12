package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.flow.FlowSessionManager
import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SENDER_ID = 42L
private const val DATE_INPUT = "11/10/2026"
private val EXPECTED_TRAINING_DATE = LocalDate.of(2026, 10, 11)
private val EXPECTED_TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

class TrainingTextHandlerTest {
    @Test
    fun `valid training date advances session and requests start time without saving`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val originalSession = UserSession(state = WaitingDate, createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            val repository = FakeTrainingRepository()
            val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
            TestTelegramBot(
                chatId = SENDER_ID,
                registerCommands = { registerTrainingTextHandler(storage, sessionManager) },
            ).use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(
                    originalSession.copy(state = WaitingStartTime(EXPECTED_TRAINING_DATE)),
                    storage.get(telegramUserId),
                )
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TIME_PROMPT)), replies)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }
}
