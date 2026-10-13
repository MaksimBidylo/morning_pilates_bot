package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private val TRAINING_DATE = LocalDate.of(2026, 10, 12)
private val TRAINING_TIME = LocalTime.of(6, 30)
private val EXPECTED_TECHNIQUE_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_technique:yes"),
            EditedTelegramInlineButton("Нет", "training:add_technique:no"),
        ),
    )

class TrainingTimeMessageTest {
    @Test
    fun `valid time edits registered question with technique buttons before deleting time message`() =
        runBlocking {
            val storage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val telegramUserId = TelegramUserId(42L)
            val chatId = TelegramChatId(4_200L)
            val commandMessageId = TelegramMessageId(713L)
            val dateMessageId = TelegramMessageId(829L)
            val timeMessageId = TelegramMessageId(947L)
            createTestBot(storage, chatId = chatId, messageSessionStorage = messageSessions).use { bot ->
                val question =
                    bot.sendCommand(
                        "/training_add",
                        senderId = telegramUserId.value,
                        messageId = commandMessageId.value,
                    )
                val dateReplies =
                    bot.sendTextAndCollectReplies("12.10.2026", telegramUserId.value, dateMessageId.value)
                assertEquals(emptyList(), dateReplies)
                val sessionBeforeTime = assertNotNull(storage.get(telegramUserId))
                assertEquals(WaitingStartTime(TRAINING_DATE), sessionBeforeTime.state)
                val registeredQuestion = assertNotNull(messageSessions.get(telegramUserId))
                assertEquals(sessionBeforeTime.sessionId, registeredQuestion.sessionId)
                assertEquals(chatId, registeredQuestion.chatId)
                assertEquals(TelegramMessageId(question.messageId), registeredQuestion.messageId)
                val previousApiCallCount = bot.apiCalls.size
                val previousEditCount = bot.editedMessages.size
                val previousDeletionCount = bot.deletedMessages.size

                // When
                val replies = bot.sendTextAndCollectReplies("6:30", telegramUserId.value, timeMessageId.value)

                // Then: the same session and registered question continue the conversation.
                val expectedState = WaitingAddTechnique(TrainingDetails(TRAINING_DATE, TRAINING_TIME))
                assertEquals(sessionBeforeTime.copy(state = expectedState), storage.get(telegramUserId))
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val expectedEdit =
                    EditedTelegramMessage(
                        registeredQuestion.chatId,
                        registeredQuestion.messageId,
                        "Добавить технику?",
                        EXPECTED_TECHNIQUE_KEYBOARD,
                    )
                val expectedDeletion = DeletedTelegramMessage(chatId.value, timeMessageId.value)
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
