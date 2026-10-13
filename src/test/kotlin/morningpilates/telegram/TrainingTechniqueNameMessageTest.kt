package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

private val EXPECTED_DESCRIPTION_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_technique_description:yes"),
            EditedTelegramInlineButton("Нет", "training:add_technique_description:no"),
        ),
    )

class TrainingTechniqueNameMessageTest {
    @Test
    fun `valid technique name edits registered question with description buttons before deleting name message`() =
        runBlocking {
            val storage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val telegramUserId = TelegramUserId(42L)
            val chatId = TelegramChatId(4_200L)
            val nameMessageId = TelegramMessageId(829L)
            val details =
                TrainingDetails(
                    date = LocalDate.of(2026, 10, 12),
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Triangle", "Из закрытого гарда")),
                )
            val originalSession =
                UserSession(state = WaitingTechniqueName(details), createdAt = Instant.parse("2026-10-12T03:15:00Z"))
            check(storage.start(telegramUserId, originalSession))
            val registeredQuestion =
                TrainingMessageSession(originalSession.sessionId, chatId, TelegramMessageId(6_341L))
            messageSessions.put(telegramUserId, registeredQuestion)
            // Fixture setup performs no Telegram API calls.
            createTestBot(storage, chatId = chatId, messageSessionStorage = messageSessions).use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies("Armbar", telegramUserId.value, nameMessageId.value)

                // Then: keep previous details; the new name belongs to the next state, not the techniques list.
                val expectedState = WaitingAddTechniqueDescription(details, "Armbar")
                assertEquals(originalSession.copy(state = expectedState), storage.get(telegramUserId))
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val expectedEdit =
                    EditedTelegramMessage(
                        registeredQuestion.chatId,
                        registeredQuestion.messageId,
                        "Добавить описание техники?",
                        EXPECTED_DESCRIPTION_KEYBOARD,
                    )
                val expectedDeletion = DeletedTelegramMessage(chatId.value, nameMessageId.value)
                assertAll(
                    { assertEquals(listOf(expectedEdit), bot.editedMessages) },
                    { assertEquals(emptyList(), replies) },
                    { assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls) },
                    { assertEquals(listOf(expectedDeletion), bot.deletedMessages) },
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
