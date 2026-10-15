package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.CallbackQuestion
import morningpilates.telegram.DeletedTelegramMessage
import morningpilates.telegram.TelegramChatId
import morningpilates.telegram.TelegramMessageId
import morningpilates.telegram.TestTelegramBot
import morningpilates.telegram.TrainingMessageSession
import morningpilates.telegram.TrainingMessageSessionStorage
import morningpilates.telegram.TrainingViewStorage
import morningpilates.telegram.startSessionAwaitingConfirmation
import morningpilates.telegram.withoutQuestionToken
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TRAINING_ADD_COMMAND = "/training_add"
private const val SENDER_ID = 42L
private const val OTHER_USER_ID = 84L
private const val GROUP_CHAT_ID = -987654321L

class TrainingAddCommandTest {
    @Test
    fun `training add in private chat starts sender session and sends exactly one date request`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val chatId = telegramUserId.value
            TestTelegramBot(
                chatId = chatId,
                registerCommands = {
                    registerTrainingAddCommand(storage, TrainingMessageSessionStorage(), TrainingViewStorage())
                },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(1, replies.size)
                val reply = replies.single()
                assertEquals(WaitingDate, storage.get(telegramUserId)?.state)
                assertEquals(chatId, reply.chatId)
                assertEquals(
                    """
                    📅 Введите дату тренировки.

                    Например: 11.10.2026 или 2026-10-11.

                    Можно использовать разделители: точку, дефис или косую черту.
                    """.trimIndent(),
                    reply.text,
                )
            }
        }

    @ParameterizedTest(name = "command message {0} is linked to API response message {1}")
    @CsvSource("713, 9341", "829, 4294967311")
    fun `training add registers API returned message id with the created session`(
        commandMessageId: Long,
        apiMessageId: Long,
    ) = runBlocking {
        // Given
        val storage = InMemoryFlowStorage()
        val messageSessions = TrainingMessageSessionStorage()
        val telegramUserId = TelegramUserId(SENDER_ID)
        val chatId = 4_200L
        TestTelegramBot(
            chatId = chatId,
            firstBotMessageId = apiMessageId,
            registerCommands = { registerTrainingAddCommand(storage, messageSessions, TrainingViewStorage()) },
        ).use { bot ->
            // When
            val reply =
                bot.sendCommand(
                    TRAINING_ADD_COMMAND,
                    senderId = telegramUserId.value,
                    messageId = commandMessageId,
                )

            // Then
            val domainSession = assertNotNull(storage.get(telegramUserId))
            assertEquals(apiMessageId, reply.messageId)
            assertEquals(
                TrainingMessageSession(
                    sessionId = domainSession.sessionId,
                    chatId = TelegramChatId(chatId),
                    messageId = TelegramMessageId(reply.messageId),
                ),
                messageSessions.get(telegramUserId),
                "The API response message must be registered for the created domain session",
            )
            assertNull(messageSessions.get(TelegramUserId(OTHER_USER_ID)))
        }
    }

    @Test
    fun `training add sends date request before deleting only the incoming command message`() =
        runBlocking {
            // Given
            val storage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val chatId = 4_200L
            val commandMessageId = 829L
            TestTelegramBot(
                chatId = chatId,
                firstBotMessageId = 9_341L,
                registerCommands = {
                    registerStartCommand()
                    registerTrainingAddCommand(storage, messageSessions, TrainingViewStorage())
                },
            ).use { bot ->
                bot.sendCommand("/start", senderId = SENDER_ID, messageId = 713L)
                val previousApiCallCount = bot.apiCalls.size

                // When
                bot.sendCommand(TRAINING_ADD_COMMAND, senderId = SENDER_ID, messageId = commandMessageId)

                // Then
                assertEquals(
                    listOf("sendMessage", "deleteMessage"),
                    bot.apiCalls.drop(previousApiCallCount),
                    "The date request must be sent before the command message is deleted",
                )
                assertEquals(
                    listOf(DeletedTelegramMessage(chatId, commandMessageId)),
                    bot.deletedMessages,
                    "Only the user message containing /training_add must be deleted",
                )
            }
        }

    @Test
    fun `training add preserves the complete domain session passed to storage`() =
        runBlocking {
            // Given
            val storage = RecordingFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            TestTelegramBot(
                chatId = SENDER_ID,
                registerCommands = {
                    registerTrainingAddCommand(storage, TrainingMessageSessionStorage(), TrainingViewStorage())
                },
            ).use { bot ->
                // When
                bot.sendCommand(TRAINING_ADD_COMMAND, senderId = telegramUserId.value, messageId = 713L)

                // Then
                assertEquals(1, storage.startCalls)
                val createdSession = assertNotNull(storage.startedSession)
                assertEquals(WaitingDate, createdSession.state)
                assertEquals(createdSession, storage.get(telegramUserId))
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
                registerCommands = {
                    registerTrainingAddCommand(storage, TrainingMessageSessionStorage(), TrainingViewStorage())
                },
            ).use { bot ->
                val first = bot.sendCommand(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)
                val originalQuestion = bot.currentMessage(TelegramMessageId(first.messageId))
                val originalSession = checkNotNull(storage.get(telegramUserId))

                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertEquals(1, replies.size)
                val warning =
                    "⚠️ <b>У вас есть незавершённая тренировка</b>\n\n" +
                        "Продолжите или отмените её, прежде чем создавать новую."
                assertEquals(
                    "$warning\n\n${originalQuestion.text}",
                    replies.single().text,
                )
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
                assertEquals(emptyList(), bot.editedMessages)
                assertEquals(originalQuestion, bot.currentMessage(originalQuestion.messageId))
            }
        }

    @Test
    fun `training add preserves advanced session with all details and restores its unregistered question`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val chatId = telegramUserId.value
            val expectedSession = startSessionAwaitingConfirmation(storage, telegramUserId)
            val messageSessions = TrainingMessageSessionStorage()
            val commandMessageId = TelegramMessageId(829L)
            TestTelegramBot(
                chatId = chatId,
                registerCommands = { registerTrainingAddCommand(storage, messageSessions, TrainingViewStorage()) },
            ).use { bot ->
                // When
                val reply =
                    bot.sendCommand(
                        TRAINING_ADD_COMMAND,
                        senderId = telegramUserId.value,
                        messageId = commandMessageId.value,
                    )

                // Then
                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(chatId, reply.chatId)
                assertEquals("💾 Сохранить тренировку?", reply.text)
                val registered = assertNotNull(messageSessions.get(telegramUserId))
                assertEquals(
                    TrainingMessageSession(
                        sessionId = expectedSession.sessionId,
                        chatId = TelegramChatId(chatId),
                        messageId = TelegramMessageId(reply.messageId),
                    ),
                    registered,
                )
                assertNotNull(messageSessions.questionToken(telegramUserId))
                assertEquals(
                    CallbackQuestion("💾 Сохранить тренировку?", "confirmation").at(registered),
                    bot.currentMessage(registered.messageId).withoutQuestionToken(),
                )
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls)
                assertEquals(listOf(DeletedTelegramMessage(chatId, commandMessageId.value)), bot.deletedMessages)
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
        val messageSessions = TrainingMessageSessionStorage()
        val telegramUserId = TelegramUserId(SENDER_ID)
        TestTelegramBot(
            chatId = chatId,
            chatType = chatType,
            registerCommands = { registerTrainingAddCommand(storage, messageSessions, TrainingViewStorage()) },
        ).use { bot ->
            // When
            val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

            // Then
            assertNull(storage.get(telegramUserId))
            assertNull(messageSessions.get(telegramUserId))
            assertEquals(emptyList(), replies)
            assertEquals(emptyList(), bot.deletedMessages)
        }
    }

    @Test
    fun `training add in group preserves existing private session and sends no reply`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val expectedSession = startSessionAwaitingConfirmation(storage, telegramUserId)
            TestTelegramBot(
                chatId = GROUP_CHAT_ID,
                chatType = "group",
                registerCommands = { registerTrainingAddCommand(storage, messageSessions, TrainingViewStorage()) },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = telegramUserId.value)

                // Then
                assertEquals(expectedSession, storage.get(telegramUserId))
                assertNull(messageSessions.get(telegramUserId))
                assertEquals(emptyList(), replies)
                assertEquals(emptyList(), bot.deletedMessages)
            }
        }

    @Test
    fun `training add without sender does not start session or send reply`() =
        runBlocking {
            // Given
            val storage = RecordingFlowStorage()
            TestTelegramBot(
                chatId = SENDER_ID,
                registerCommands = {
                    registerTrainingAddCommand(storage, TrainingMessageSessionStorage(), TrainingViewStorage())
                },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = null)

                // Then
                assertEquals(0, storage.startCalls)
                assertEquals(emptyList(), replies)
                assertEquals(emptyList(), bot.deletedMessages)
            }
        }

    @Test
    fun `training add without sender preserves another active session and sends no reply`() =
        runBlocking {
            // Given
            val backingStorage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val otherUserId = TelegramUserId(OTHER_USER_ID)
            val expectedSession = startSessionAwaitingConfirmation(backingStorage, otherUserId)
            val storage = RecordingFlowStorage(backingStorage)
            TestTelegramBot(
                chatId = SENDER_ID,
                registerCommands = { registerTrainingAddCommand(storage, messageSessions, TrainingViewStorage()) },
            ).use { bot ->
                // When
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = null)

                // Then
                assertEquals(expectedSession, storage.get(otherUserId))
                assertNull(messageSessions.get(otherUserId))
                assertEquals(0, storage.startCalls)
                assertEquals(emptyList(), replies)
                assertEquals(emptyList(), bot.deletedMessages)
            }
        }

    private class RecordingFlowStorage(
        private val backingStorage: FlowStorage = InMemoryFlowStorage(),
    ) : FlowStorage by backingStorage {
        var startCalls = 0
            private set
        var startedSession: UserSession? = null
            private set

        override fun start(
            userId: TelegramUserId,
            session: UserSession,
        ): Boolean {
            startCalls++
            startedSession = session
            return backingStorage.start(userId, session)
        }
    }
}
