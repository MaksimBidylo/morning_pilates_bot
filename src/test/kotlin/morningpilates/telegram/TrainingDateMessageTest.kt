package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

private const val SENDER_ID = 42L
private const val TRAINING_ADD_COMMAND = "/training_add"
private val TRAINING_DATE = LocalDate.of(2026, 10, 12)
private val EXPECTED_TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

class TrainingDateMessageTest {
    @Test
    fun `valid date edits registered question before deleting date message without sending another message`() =
        runBlocking {
            val storage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val chatId = TelegramChatId(4_200L)
            val commandMessageId = TelegramMessageId(713L)
            val dateMessageId = TelegramMessageId(829L)
            createTestBot(storage, chatId = chatId, messageSessionStorage = messageSessions).use { bot ->
                val question =
                    bot.sendCommand(TRAINING_ADD_COMMAND, senderId = SENDER_ID, messageId = commandMessageId.value)
                val originalSession = assertNotNull(storage.get(telegramUserId))
                val registeredQuestion = assertNotNull(messageSessions.get(telegramUserId))
                assertEquals(originalSession.sessionId, registeredQuestion.sessionId)
                assertEquals(chatId, registeredQuestion.chatId)
                assertEquals(TelegramMessageId(question.messageId), registeredQuestion.messageId)
                assertNotEquals(commandMessageId, registeredQuestion.messageId)
                assertNotEquals(dateMessageId, registeredQuestion.messageId)
                val previousApiCallCount = bot.apiCalls.size
                val previousEditCount = bot.editedMessages.size
                val previousDeletionCount = bot.deletedMessages.size

                // When
                val replies =
                    bot.sendTextAndCollectReplies("12.10.2026", senderId = SENDER_ID, messageId = dateMessageId.value)

                // Then: domain transition preserves the session identity and creation time.
                assertEquals(originalSession.copy(state = WaitingStartTime(TRAINING_DATE)), storage.get(telegramUserId))
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val expectedEdit =
                    EditedTelegramMessage(registeredQuestion.chatId, registeredQuestion.messageId, EXPECTED_TIME_PROMPT)
                val expectedDeletion = DeletedTelegramMessage(chatId.value, dateMessageId.value)
                assertAll(
                    { assertEquals(listOf(expectedEdit), bot.editedMessages.drop(previousEditCount)) },
                    { assertEquals(emptyList(), replies) },
                    {
                        assertEquals(
                            listOf("editMessageText", "deleteMessage"),
                            bot.apiCalls.drop(previousApiCallCount),
                        )
                    },
                    { assertEquals(listOf(expectedDeletion), bot.deletedMessages.drop(previousDeletionCount)) },
                )
            }
        }

    private fun createTestBot(
        storage: FlowStorage,
        chatId: TelegramChatId,
        messageSessionStorage: TrainingMessageSessionStorage,
    ): TestTelegramBot {
        val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(FakeTrainingRepository())))
        return TestTelegramBot(
            telegramBot =
                TelegramBot(
                    properties = TelegramProperties("test-token"),
                    flowStorage = storage,
                    sessionManager = sessionManager,
                    messageSessionStorage = messageSessionStorage,
                ),
            chatId = chatId.value,
        )
    }
}
