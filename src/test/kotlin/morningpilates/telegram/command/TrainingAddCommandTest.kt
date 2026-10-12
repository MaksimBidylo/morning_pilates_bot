package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TestTelegramBot
import morningpilates.telegram.startSessionAwaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TRAINING_ADD_COMMAND = "/training_add"
private const val SENDER_ID = 42L
private const val OTHER_USER_ID = 84L
private const val GROUP_CHAT_ID = -987654321L
private const val EXPECTED_UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."

class TrainingAddCommandTest {
    @Test
    fun `training add in private chat starts sender session and requests date`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val chatId = telegramUserId.value
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
            val telegramUserId = TelegramUserId(SENDER_ID)
            val chatId = telegramUserId.value
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
            val telegramUserId = TelegramUserId(SENDER_ID)
            val chatId = telegramUserId.value
            val expectedSession = startSessionAwaitingConfirmation(storage, telegramUserId)
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

    @ParameterizedTest(name = "training add in {0} is ignored")
    @CsvSource(
        "group, $GROUP_CHAT_ID",
        "supergroup, -100987654321",
    )
    fun `training add in group chat does not create session or send reply`(
        chatType: String,
        chatId: Long,
    ) = runBlocking {
        // Given
        val storage: FlowStorage = InMemoryFlowStorage()
        val telegramUserId = TelegramUserId(SENDER_ID)
        TestTelegramBot(
            chatId = chatId,
            chatType = chatType,
            registerCommands = { registerTrainingAddCommand(storage) },
        ).use { bot ->
            // When
            val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

            // Then
            assertNull(storage.get(telegramUserId))
            assertEquals(emptyList(), replies)
        }
    }

    @Test
    fun `training add in group preserves existing private session and sends no reply`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val expectedSession = startSessionAwaitingConfirmation(storage, telegramUserId)
            TestTelegramBot(
                chatId = GROUP_CHAT_ID,
                chatType = "group",
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(emptyList(), replies)
            }
        }

    @Test
    fun `training add without sender does not start session or send reply`() =
        runBlocking {
            // Given
            val storage = RecordingFlowStorage()
            TestTelegramBot(
                chatId = SENDER_ID,
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = null)

                // Then
                assertEquals(0, storage.startCalls)
                assertEquals(emptyList(), replies)
            }
        }

    @Test
    fun `training add without sender preserves another active session and sends no reply`() =
        runBlocking {
            // Given
            val backingStorage = InMemoryFlowStorage()
            val otherUserId = TelegramUserId(OTHER_USER_ID)
            val expectedSession = startSessionAwaitingConfirmation(backingStorage, otherUserId)
            val storage = RecordingFlowStorage(backingStorage)
            TestTelegramBot(
                chatId = SENDER_ID,
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = null)

                // Then
                assertEquals(expectedSession, storage.get(otherUserId))
                assertEquals(0, storage.startCalls)
                assertEquals(emptyList(), replies)
            }
        }

    private class RecordingFlowStorage(
        private val backingStorage: FlowStorage = InMemoryFlowStorage(),
    ) : FlowStorage by backingStorage {
        var startCalls = 0
            private set

        override fun start(
            userId: TelegramUserId,
            session: UserSession,
        ): Boolean {
            startCalls++
            return backingStorage.start(userId, session)
        }
    }
}
