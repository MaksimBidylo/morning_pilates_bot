package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DeletedTelegramMessage
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.sentTrainingQuestion
import morningpilates.telegram.support.trainingQuestion
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MISSING_QUESTION = "Bad Request: message to edit not found"
private val RECOVERY_DATE = LocalDate.of(2026, 10, 12)
private val RECOVERY_TIME = LocalTime.of(6, 30)
private val NEXT_TIME_QUESTION =
    """
    🕒 Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

internal class TrainingCreationActiveMessageRecoveryTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val dateMessageId = TelegramMessageId(829L)
    private val nameMessageId = TelegramMessageId(1_123L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val events = mutableListOf<FlowEvent>()
    private val dateInput: FlowEvent = FlowEvent.UserInput(userId, "12.10.2026")

    @Test
    fun `missing date question is replaced after one transition and the new cancel button works`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                val before = assertNotNull(storage.get(userId))
                val original = assertNotNull(messages.get(userId))
                val oldQuestion = bot.currentMessage(original.messageId)
                val staleCancel = bot.captureCallback(oldQuestion, "Отменить тренировку", userId)
                val calls = bot.apiCalls.size
                val deletions = bot.deletedMessages.size
                val eventCount = events.size
                bot.failNextEditMessageText(MISSING_QUESTION)

                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                assertAll(
                    { assertEquals(before.copy(state = WaitingStartTime(RECOVERY_DATE)), storage.get(userId)) },
                    { assertEquals(listOf(dateInput), events.drop(eventCount)) },
                    {
                        assertEquals(
                            listOf("editMessageText", "sendMessage", "deleteMessage"),
                            bot.apiCalls.drop(calls),
                        )
                    },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, dateMessageId.value)),
                            bot.deletedMessages.drop(deletions),
                        )
                    },
                    { assertEquals(listOf(sentTrainingQuestion(chatId.value, NEXT_TIME_QUESTION)), replies) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                val replacement = assertNotNull(messages.get(userId))
                assertEquals(before.sessionId, replacement.sessionId)
                assertEquals(chatId, replacement.chatId)
                assertNotEquals(original.messageId, replacement.messageId)
                assertEquals(TelegramMessageId(replies.single().messageId), replacement.messageId)
                assertEquals(
                    trainingQuestion(chatId, replacement.messageId, NEXT_TIME_QUESTION),
                    bot.currentMessage(replacement.messageId).withoutQuestionToken(),
                )
                assertNotNull(messages.questionToken(userId))
                assertIgnored(bot, staleCancel)
                val current = bot.currentMessage(replacement.messageId)
                val cancel = bot.pressInlineButtonAndCollectReplies(current, "Отменить тренировку", userId.value)
                assertTrue(cancel.acknowledged)
                assertTrue(cancel.replies.isEmpty())
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertEquals(
                    EditedTelegramMessage(chatId, replacement.messageId, "Создание тренировки отменено."),
                    bot.currentMessage(replacement.messageId),
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `missing inline question is replaced without replaying yes and name input edits its replacement`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)
                bot.sendTextAndCollectReplies("6:30", userId.value, 947L)
                val before = assertNotNull(storage.get(userId))
                val details = TrainingDetails(RECOVERY_DATE, RECOVERY_TIME)
                assertEquals(WaitingAddTechnique(details), before.state)
                val original = assertNotNull(messages.get(userId))
                val oldQuestion = bot.currentMessage(original.messageId)
                val yes = bot.captureCallback(oldQuestion, "Да", userId)
                val staleCancel = bot.captureCallback(oldQuestion, "Отменить тренировку", userId)
                val calls = bot.apiCalls.size
                val eventCount = events.size
                val deletions = bot.deletedMessages
                bot.failNextEditMessageText(MISSING_QUESTION)

                val result = bot.deliverCallbackAndCollectReplies(yes)

                assertAll(
                    { assertEquals(before.copy(state = WaitingTechniqueName(details)), storage.get(userId)) },
                    { assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "Да")), events.drop(eventCount)) },
                    { assertTrue(result.acknowledged) },
                    {
                        assertEquals(
                            listOf(sentTrainingQuestion(chatId.value, "🏷️ Введите название техники.")),
                            result.replies,
                        )
                    },
                    {
                        assertEquals(
                            listOf("editMessageText", "sendMessage", "answerCallbackQuery"),
                            bot.apiCalls.drop(calls),
                        )
                    },
                    { assertEquals(deletions, bot.deletedMessages) },
                )
                val replacement = assertNotNull(messages.get(userId))
                assertEquals(original.sessionId, replacement.sessionId)
                assertEquals(chatId, replacement.chatId)
                assertNotEquals(original.messageId, replacement.messageId)
                assertEquals(TelegramMessageId(result.replies.single().messageId), replacement.messageId)
                assertIgnored(bot, yes)
                assertIgnored(bot, staleCancel)
                assertNameContinues(bot, replacement)
            }
        }

    @ParameterizedTest(name = "unknown edit error {0} does not automatically replace the question")
    @CsvSource("503, Telegram temporarily unavailable", "400, Bad Request: can't parse entities")
    fun `temporary or unknown edit failure preserves the accepted date without sending or deleting`(
        statusCode: Int,
        description: String,
    ) = runBlocking<Unit> {
        createBot().use { bot ->
            bot.sendCommand("/training_add", userId.value, 713L)
            val before = assertNotNull(storage.get(userId))
            val registered = assertNotNull(messages.get(userId))
            val originalQuestion = bot.currentMessage(registered.messageId)
            val calls = bot.apiCalls.size
            val eventCount = events.size
            val deletions = bot.deletedMessages
            bot.respondToNextEditMessageText(
                statusCode,
                """{"ok":false,"error_code":$statusCode,"description":${JsonPrimitive(description)}}""",
            )

            val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

            assertEquals(before.copy(state = WaitingStartTime(RECOVERY_DATE)), storage.get(userId))
            assertEquals(listOf(dateInput), events.drop(eventCount))
            assertEquals(registered, messages.get(userId))
            assertEquals(originalQuestion, bot.currentMessage(registered.messageId))
            assertEquals(listOf("editMessageText"), bot.apiCalls.drop(calls))
            assertEquals(deletions, bot.deletedMessages)
            assertTrue(replies.isEmpty())
            assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
            assertTrue(repository.saveCalls.isEmpty())
            val pendingSession = storage.get(userId)
            val callsBeforeRecovery = bot.apiCalls.size
            val eventsBeforeRecovery = events.size
            val recoveryCommandId = TelegramMessageId(2_713L)

            val recovered = bot.sendCommandAndCollectReplies("/training_add", userId.value, recoveryCommandId.value)

            assertEquals(pendingSession, storage.get(userId))
            val replacement = assertNotNull(messages.get(userId))
            assertEquals(registered.sessionId, replacement.sessionId)
            assertNotEquals(registered.messageId, replacement.messageId)
            assertEquals(TelegramMessageId(recovered.single().messageId), replacement.messageId)
            assertEquals(eventsBeforeRecovery, events.size)
            assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(callsBeforeRecovery))
            assertEquals(
                trainingQuestion(chatId, replacement.messageId, NEXT_TIME_QUESTION),
                bot.currentMessage(replacement.messageId).withoutQuestionToken(),
            )
            assertNotNull(messages.questionToken(userId))
            assertEquals(
                deletions + DeletedTelegramMessage(chatId.value, recoveryCommandId.value),
                bot.deletedMessages,
            )
        }
    }

    @Test
    fun `failed replacement send preserves the transitioned session and keeps the date input`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                val before = assertNotNull(storage.get(userId))
                val original = assertNotNull(messages.get(userId))
                val calls = bot.apiCalls.size
                val eventCount = events.size
                val deletions = bot.deletedMessages
                bot.failNextEditMessageText(MISSING_QUESTION)
                bot.failNextSendMessage("Bad Request: chat not found")

                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                assertAll(
                    { assertEquals(before.copy(state = WaitingStartTime(RECOVERY_DATE)), storage.get(userId)) },
                    { assertEquals(listOf(dateInput), events.drop(eventCount)) },
                    { assertEquals(listOf("editMessageText", "sendMessage"), bot.apiCalls.drop(calls)) },
                    { assertEquals(NEXT_TIME_QUESTION, bot.sendMessageRequests.last().text) },
                    { assertEquals(chatId, bot.sendMessageRequests.last().chatId) },
                    { assertTrue(replies.isEmpty()) },
                    { assertEquals(deletions, bot.deletedMessages) },
                    {
                        // Keep the last known registration or invalidate it; never register a failed send.
                        messages.get(userId)?.let { assertEquals(original, it) }
                    },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                val pendingSession = storage.get(userId)
                val callsBeforeRecovery = bot.apiCalls.size
                val recoveryCommandId = TelegramMessageId(2_713L)

                val recovered = bot.sendCommandAndCollectReplies("/training_add", userId.value, recoveryCommandId.value)

                val replacement = assertNotNull(messages.get(userId))
                assertEquals(pendingSession, storage.get(userId))
                assertEquals(listOf(dateInput), events.drop(eventCount))
                assertEquals(original.sessionId, replacement.sessionId)
                assertNotEquals(original.messageId, replacement.messageId)
                assertEquals(TelegramMessageId(recovered.single().messageId), replacement.messageId)
                assertEquals(
                    listOf("sendMessage", "deleteMessage"),
                    bot.apiCalls.drop(callsBeforeRecovery),
                )
                assertEquals(
                    trainingQuestion(chatId, replacement.messageId, NEXT_TIME_QUESTION),
                    bot.currentMessage(replacement.messageId).withoutQuestionToken(),
                )
                assertEquals(
                    deletions + DeletedTelegramMessage(chatId.value, recoveryCommandId.value),
                    bot.deletedMessages,
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private suspend fun assertNameContinues(
        bot: TestTelegramBot,
        replacement: TrainingCreationMessageSession,
    ) {
        val before = assertNotNull(storage.get(userId))
        val details = (before.state as WaitingTechniqueName).details
        val callsBeforeName = bot.apiCalls.size
        val eventsBeforeName = events.size

        val nameReplies = bot.sendTextAndCollectReplies("Armbar", userId.value, nameMessageId.value)

        assertEquals(
            before.copy(state = WaitingAddTechniqueDescription(details, "Armbar")),
            storage.get(userId),
        )
        assertEquals(replacement, messages.get(userId))
        assertTrue(nameReplies.isEmpty())
        assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callsBeforeName))
        assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "Armbar")), events.drop(eventsBeforeName))
        assertEquals(
            trainingQuestion(
                chatId,
                replacement.messageId,
                "📝 Добавить описание техники?",
                listOf(
                    listOf(
                        EditedTelegramInlineButton("Да", "training:add_technique_description:yes"),
                        EditedTelegramInlineButton("Нет", "training:add_technique_description:no"),
                    ),
                ),
            ),
            bot.currentMessage(replacement.messageId).withoutQuestionToken(),
        )
        assertEquals(DeletedTelegramMessage(chatId.value, nameMessageId.value), bot.deletedMessages.last())
        assertTrue(repository.saveCalls.isEmpty())
    }

    private suspend fun assertIgnored(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val session = storage.get(userId)
        val registration = messages.get(userId)
        val token = messages.questionToken(userId)
        val eventCount = events.size
        val calls = bot.apiCalls.size
        val edits = bot.editedMessages
        val deletions = bot.deletedMessages
        val replies = bot.deliverCallbackAndCollectReplies(callback)
        assertEquals(session, storage.get(userId))
        assertEquals(registration, messages.get(userId))
        assertEquals(token, messages.questionToken(userId))
        assertEquals(eventCount, events.size)
        assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
        assertEquals(edits, bot.editedMessages)
        assertEquals(deletions, bot.deletedMessages)
        assertTrue(replies.replies.isEmpty())
        assertTrue(replies.acknowledged)
        assertTrue(repository.saveCalls.isEmpty())
    }

    private fun createBot(): TestTelegramBot {
        val service = TrainingFlowService(repository)
        val observed =
            object : FlowHandler by service {
                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ) = service.handle(state, event).also { events += event }
            }
        val manager = FlowSessionManager(storage, listOf(observed))
        return TestTelegramBot(
            TelegramBot(
                TelegramProperties("test-token"),
                storage,
                manager,
                messages,
                queryService = TrainingQueryService(repository),
                deleteTraining = repository::delete,
            ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )
    }
}
