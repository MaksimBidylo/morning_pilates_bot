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
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingNote
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
private val TRAINING_TIME = LocalTime.of(6, 30)
private val EXPECTED_TECHNIQUE_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_technique:yes"),
            EditedTelegramInlineButton("Нет", "training:add_technique:no"),
        ),
    )
private val EXPECTED_DESCRIPTION_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_technique_description:yes"),
            EditedTelegramInlineButton("Нет", "training:add_technique_description:no"),
        ),
    )
private val TRAINING_DETAILS =
    TrainingDetails(
        date = TRAINING_DATE,
        time = TRAINING_TIME,
        techniques = listOf(TechniqueDraft("Triangle", "Из закрытого гарда")),
    )
private val CREATED_AT = Instant.parse("2026-10-12T03:15:00Z")
private val EXPECTED_ANOTHER_TECHNIQUE_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_another_technique:yes"),
            EditedTelegramInlineButton("Нет", "training:add_another_technique:no"),
        ),
    )
private val EXPECTED_CONFIRMATION_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:confirmation:yes"),
            EditedTelegramInlineButton("Нет", "training:confirmation:no"),
        ),
    )

class TrainingTextMessageTest {
    private val storage = InMemoryFlowStorage()
    private val messageSessions = TrainingMessageSessionStorage()
    private val telegramUserId = TelegramUserId(SENDER_ID)
    private val chatId = TelegramChatId(4_200L)
    private val sessionUpdates = mutableListOf<UserSession>()

    @Test
    fun `valid date edits registered question before deleting date message without sending another message`() =
        runBlocking {
            val commandMessageId = TelegramMessageId(713L)
            val dateMessageId = TelegramMessageId(829L)
            createTestBot().use { bot ->
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

    @Test
    fun `valid time edits registered question with technique buttons before deleting time message`() =
        runBlocking {
            val commandMessageId = TelegramMessageId(713L)
            val dateMessageId = TelegramMessageId(829L)
            val timeMessageId = TelegramMessageId(947L)
            createTestBot().use { bot ->
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

    @Test
    fun `valid technique name edits registered question with description buttons before deleting name message`() =
        runBlocking {
            val nameMessageId = TelegramMessageId(829L)
            val details = TRAINING_DETAILS
            val originalSession =
                UserSession(state = WaitingTechniqueName(details), createdAt = CREATED_AT)
            check(storage.start(telegramUserId, originalSession))
            val registeredQuestion =
                TrainingMessageSession(originalSession.sessionId, chatId, TelegramMessageId(6_341L))
            messageSessions.put(telegramUserId, registeredQuestion)
            // Fixture setup performs no Telegram API calls.
            createTestBot().use { bot ->
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

    @Test
    fun `valid technique description edits registered question before deleting description message`() =
        runBlocking {
            val description = "Рычаг локтя из маунта"
            val descriptionMessageId = TelegramMessageId(829L)
            val originalSession =
                UserSession(WaitingTechniqueDescription(TRAINING_DETAILS, "Armbar"), createdAt = CREATED_AT)
            check(storage.start(telegramUserId, originalSession))
            val registeredQuestion =
                TrainingMessageSession(originalSession.sessionId, chatId, TelegramMessageId(6_341L))
            messageSessions.put(telegramUserId, registeredQuestion)
            createTestBot().use { bot ->
                val replies =
                    bot.sendTextAndCollectReplies(description, telegramUserId.value, descriptionMessageId.value)

                val expectedDetails =
                    TRAINING_DETAILS.copy(
                        techniques = TRAINING_DETAILS.techniques + TechniqueDraft("Armbar", description),
                    )
                val expectedSession = originalSession.copy(state = WaitingAddAnotherTechnique(expectedDetails))
                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(listOf(expectedSession), sessionUpdates)
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val expectedEdit =
                    EditedTelegramMessage(
                        registeredQuestion.chatId,
                        registeredQuestion.messageId,
                        "Добавить ещё одну технику?",
                        EXPECTED_ANOTHER_TECHNIQUE_KEYBOARD,
                    )
                val expectedDeletion = DeletedTelegramMessage(chatId.value, descriptionMessageId.value)
                assertAll(
                    { assertEquals(listOf(expectedEdit), bot.editedMessages) },
                    { assertEquals(emptyList(), replies) },
                    { assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls) },
                    { assertEquals(listOf(expectedDeletion), bot.deletedMessages) },
                )
            }
        }

    @Test
    fun `valid note edits registered question before deleting note message`() =
        runBlocking {
            val note = "Отрабатывали выход из сайд-контроля"
            val noteMessageId = TelegramMessageId(947L)
            val originalSession = UserSession(WaitingNote(TRAINING_DETAILS), createdAt = CREATED_AT)
            check(storage.start(telegramUserId, originalSession))
            val registeredQuestion =
                TrainingMessageSession(originalSession.sessionId, chatId, TelegramMessageId(7_341L))
            messageSessions.put(telegramUserId, registeredQuestion)
            // Fixture setup performs no Telegram API calls or session updates.
            createTestBot().use { bot ->
                val replies = bot.sendTextAndCollectReplies(note, telegramUserId.value, noteMessageId.value)

                val expectedSession =
                    originalSession.copy(state = WaitingConfirmation(TRAINING_DETAILS.copy(note = note)))
                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(listOf(expectedSession), sessionUpdates)
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val expectedEdit =
                    EditedTelegramMessage(
                        registeredQuestion.chatId,
                        registeredQuestion.messageId,
                        "Сохранить тренировку?",
                        EXPECTED_CONFIRMATION_KEYBOARD,
                    )
                val expectedDeletion = DeletedTelegramMessage(chatId.value, noteMessageId.value)
                assertAll(
                    { assertEquals(listOf(expectedEdit), bot.editedMessages) },
                    { assertEquals(emptyList(), replies) },
                    { assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls) },
                    { assertEquals(listOf(expectedDeletion), bot.deletedMessages) },
                )
            }
        }

    private fun createTestBot(): TestTelegramBot {
        val observedStorage =
            object : FlowStorage by storage {
                override fun update(
                    userId: TelegramUserId,
                    expected: UserSession,
                    updated: UserSession,
                ): Boolean {
                    sessionUpdates += updated
                    return storage.update(userId, expected, updated)
                }
            }
        val sessionManager = FlowSessionManager(observedStorage, listOf(TrainingFlowService(FakeTrainingRepository())))
        return TestTelegramBot(
            telegramBot =
                TelegramBot(
                    properties = TelegramProperties("test-token"),
                    flowStorage = observedStorage,
                    sessionManager = sessionManager,
                    messageSessionStorage = messageSessions,
                ),
            chatId = chatId.value,
        )
    }
}
