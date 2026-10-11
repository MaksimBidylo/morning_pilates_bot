package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TestTelegramBot
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.populatedTrainingDetails
import morningpilates.training.snapshot
import morningpilates.user.TelegramUserId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TRAINING_ADD_COMMAND = "/training_add"
private const val EXPECTED_UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."

class TrainingAddCommandTest {
    @Test
    fun `training add in private chat starts sender session and requests date`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(42L)
            val chatId = 123456789L
            TestTelegramBot(
                chatId = chatId,
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                // When
                val reply = bot.sendCommand(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(WaitingDate, storage.get(telegramUserId)?.state)
                assertEquals(chatId, reply.chatId)
                assertEquals(
                    """
                    Введите дату тренировки.

                    Например: 11.10.2026 или 2026-10-11.

                    Можно использовать разделители: точку, дефис или косую черту.
                    """.trimIndent(),
                    reply.text,
                )
            }
        }

    @Test
    fun `repeated training add preserves initial session and reports unfinished training`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(42L)
            val chatId = 123456789L
            TestTelegramBot(
                chatId = chatId,
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                bot.sendCommand(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)
                val originalSession = checkNotNull(storage.get(telegramUserId))

                // When
                val reply = bot.sendCommand(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertEquals(chatId, reply.chatId)
                assertEquals(EXPECTED_UNFINISHED_TRAINING_MESSAGE, reply.text)
            }
        }

    @Test
    fun `training add preserves advanced session with all details and reports unfinished training`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(42L)
            val chatId = 123456789L
            val details = populatedTrainingDetails()
            val originalSession =
                UserSession(
                    state = WaitingConfirmation(details),
                    createdAt = Instant.EPOCH,
                )
            val expectedSession = originalSession.copy(state = WaitingConfirmation(details.snapshot()))
            check(storage.start(telegramUserId, originalSession))
            TestTelegramBot(
                chatId = chatId,
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                // When
                val reply = bot.sendCommand(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(chatId, reply.chatId)
                assertEquals(EXPECTED_UNFINISHED_TRAINING_MESSAGE, reply.text)
            }
        }
}
