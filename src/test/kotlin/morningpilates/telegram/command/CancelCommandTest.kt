package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.model.TestFlowState.INITIAL
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DeletedTelegramMessage
import morningpilates.telegram.support.EXPECTED_CANCEL_ROW
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.telegram.training.creation.TrainingCreationMessageSession
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.Saving
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingNote
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val DATE = LocalDate.of(2026, 10, 12)
private val TIME = LocalTime.of(6, 30)
private val DETAILS = TrainingDetails(DATE, TIME)
private const val DESCRIPTION = "Рычаг локтя из маунта"
private val WITH_TECHNIQUE = DETAILS.copy(techniques = listOf(TechniqueDraft("Armbar", DESCRIPTION)))
private val DATE_TIME_INPUTS = listOf("12.10.2026", "6:30")
private val NAME_INPUTS = DATE_TIME_INPUTS + listOf("Да", "Armbar")
private val DESCRIPTION_INPUTS = NAME_INPUTS + "Да"
private val ANOTHER_INPUTS = DESCRIPTION_INPUTS + DESCRIPTION
private val NOTE_CHOICE_INPUTS = ANOTHER_INPUTS + "Нет"
private val NOTE_INPUTS = NOTE_CHOICE_INPUTS + "Да"

internal class CancelCommandTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val cancelMessageId = TelegramMessageId(2_713L)
    private val domainEvents = mutableListOf<FlowEvent>()
    private val domainResults = mutableListOf<FlowResult>()

    @Test
    fun `cancel of another flow uses its handler and a neutral confirmation`() =
        runBlocking<Unit> {
            val original = UserSession(INITIAL, Instant.EPOCH)
            check(storage.start(userId, original))
            val handler =
                object : FlowHandler {
                    override fun supports(state: FlowState): Boolean = state == INITIAL

                    override fun handle(
                        state: FlowState,
                        event: FlowEvent,
                    ): FlowResult {
                        assertEquals(INITIAL, state)
                        assertEquals(FlowEvent.Cancel(userId), event)
                        return FlowResult.Cancelled
                    }
                }
            createBot(handler).use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                assertAll(
                    { assertNull(storage.get(userId)) },
                    { assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents) },
                    { assertEquals(listOf<FlowResult>(FlowResult.Cancelled), domainResults) },
                    { assertEquals(listOf(SentTelegramMessage(chatId.value, "Действие отменено.")), replies) },
                    { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls) },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, cancelMessageId.value)),
                            bot.deletedMessages,
                        )
                    },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @Test
    fun `cancel during saving without registration reports rejection and preserves the pending save`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.failNextSendMessage("Bad Request: chat not found")
                bot.sendCommandAndCollectReplies("/training_add", userId.value, 713L)
                val original = assertNotNull(storage.get(userId))
                val saving = original.copy(state = Saving(WITH_TECHNIQUE))
                check(storage.update(userId, original, saving))
                assertNull(messages.get(userId))
                val callCount = bot.apiCalls.size

                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                val reason = "Тренировка сохраняется. Дождитесь завершения."
                assertAll(
                    { assertEquals(saving, storage.get(userId)) },
                    { assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents) },
                    { assertEquals(listOf<FlowResult>(FlowResult.Rejected(reason)), domainResults) },
                    { assertEquals(listOf(SentTelegramMessage(chatId.value, reason)), replies) },
                    { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(callCount)) },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, cancelMessageId.value)),
                            bot.deletedMessages,
                        )
                    },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @ParameterizedTest(name = "failed cancellation edit via {0} clears the old registration")
    @ValueSource(strings = ["command", "inline", "confirmation no"])
    fun `failed cancellation edit clears registration without another domain operation`(action: String) =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                if (action == "confirmation no") enterInputs(bot, DATE_TIME_INPUTS + listOf("Нет", "Нет"))
                val original = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val question = bot.currentMessage(registered.messageId)
                val button = if (action == "confirmation no") "Нет" else "Отменить тренировку"
                val callback = bot.captureCallback(question, button, userId)
                val eventCount = domainEvents.size
                val resultCount = domainResults.size
                val calls = bot.apiCalls.size
                val deletions = bot.deletedMessages
                bot.failNextEditMessageText("Bad Request: message to edit not found")

                if (action == "command") {
                    val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)
                    assertTrue(replies.isEmpty())
                } else {
                    val interaction = bot.deliverCallbackAndCollectReplies(callback)
                    assertTrue(interaction.acknowledged)
                    assertTrue(interaction.replies.isEmpty())
                }

                val expectedEvent =
                    if (action == "confirmation no") FlowEvent.UserInput(userId, "Нет") else FlowEvent.Cancel(userId)
                val expectedCalls =
                    listOf("editMessageText") + if (action == "command") emptyList() else listOf("answerCallbackQuery")
                assertAll(
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertEquals(listOf(expectedEvent), domainEvents.drop(eventCount)) },
                    { assertEquals(listOf<FlowResult>(FlowResult.Cancelled), domainResults.drop(resultCount)) },
                    { assertEquals(expectedCalls, bot.apiCalls.drop(calls)) },
                    { assertEquals(question, bot.currentMessage(registered.messageId)) },
                    { assertEquals(deletions, bot.deletedMessages) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                bot.sendCommand("/training_add", userId.value, 2_829L)
                val next = assertNotNull(storage.get(userId))
                val nextMessage = assertNotNull(messages.get(userId))
                assertNotEquals(original.sessionId, next.sessionId)
                assertNotEquals(registered.messageId, nextMessage.messageId)
                assertEquals(next.sessionId, nextMessage.sessionId)
                val nextCalls = bot.apiCalls.size
                val nextEvents = domainEvents.toList()
                val stale = bot.deliverCallbackAndCollectReplies(callback)
                assertTrue(stale.acknowledged)
                assertTrue(stale.replies.isEmpty())
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(nextCalls))
                assertEquals(nextEvents, domainEvents)
                assertEquals(next, storage.get(userId))
                assertEquals(nextMessage, messages.get(userId))
            }
        }

    @Test
    fun `cancel after failed initial question terminates the unregistered session`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.failNextSendMessage("Bad Request: chat not found")
                val initialReplies = bot.sendCommandAndCollectReplies("/training_add", userId.value, 713L)
                val original = assertNotNull(storage.get(userId))
                assertEquals(WaitingDate, original.state)
                assertTrue(initialReplies.isEmpty())
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertTrue(bot.deletedMessages.isEmpty())
                assertTrue(domainEvents.isEmpty())

                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                assertAll(
                    { assertNull(storage.get(userId)) },
                    { assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents) },
                    { assertEquals(listOf<FlowResult>(FlowResult.Cancelled), domainResults) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    {
                        assertEquals(
                            expected = listOf(SentTelegramMessage(chatId.value, "Создание тренировки отменено.")),
                            actual = replies,
                        )
                    },
                    { assertEquals(listOf("sendMessage", "sendMessage", "deleteMessage"), bot.apiCalls) },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, cancelMessageId.value)),
                            bot.deletedMessages,
                        )
                    },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )

                bot.sendCommand("/training_add", userId.value, 2_829L)
                val next = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                assertNotEquals(original.sessionId, next.sessionId)
                assertEquals(WaitingDate, next.state)
                assertEquals(next.sessionId, registered.sessionId)
                assertNotNull(messages.questionToken(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed cancellation notification cannot restore the unregistered session or delete the command`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.failNextSendMessage("Bad Request: chat not found")
                bot.sendCommandAndCollectReplies("/training_add", userId.value, 713L)
                val original = assertNotNull(storage.get(userId))
                assertNull(messages.get(userId))
                bot.failNextSendMessage("Bad Request: chat not found")

                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                assertNull(storage.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents)
                assertEquals(listOf<FlowResult>(FlowResult.Cancelled), domainResults)
                assertTrue(replies.isEmpty())
                assertEquals(listOf("sendMessage", "sendMessage"), bot.apiCalls)
                assertEquals("Создание тренировки отменено.", bot.sendMessageRequests.last().text)
                val notification = bot.sendMessageRequests.last()
                assertTrue(notification.inlineKeyboard.isEmpty())
                assertTrue(bot.deleteMessageAttempts.isEmpty())
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertTrue(repository.saveCalls.isEmpty())

                bot.sendCommand("/training_add", userId.value, 2_829L)
                val next = assertNotNull(storage.get(userId))
                assertNotEquals(original.sessionId, next.sessionId)
                assertEquals(WaitingDate, next.state)
                assertEquals(next.sessionId, assertNotNull(messages.get(userId)).sessionId)
                assertNotNull(messages.questionToken(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents)
            }
        }

    @Test
    fun `cancel without a session replies before deleting the command`() =
        runBlocking {
            createBot().use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                assertEquals(listOf(SentTelegramMessage(chatId.value, "Нет активного действия.")), replies)
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls)
                assertEquals(listOf(DeletedTelegramMessage(chatId.value, cancelMessageId.value)), bot.deletedMessages)
                assertTrue(bot.editedMessages.isEmpty())
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertTrue(domainEvents.isEmpty())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `cancel during saving is rejected by the domain and preserves the pending save`() =
        runBlocking {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value)
                val original = assertNotNull(storage.get(userId))
                val saving = original.copy(state = Saving(WITH_TECHNIQUE))
                check(storage.update(userId, original, saving))
                val registration = assertNotNull(messages.get(userId))
                val callCount = bot.apiCalls.size

                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                assertAll(
                    { assertEquals(saving, storage.get(userId)) },
                    { assertEquals(registration, messages.get(userId)) },
                    { assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), domainEvents) },
                    {
                        assertEquals(
                            listOf<FlowResult>(FlowResult.Rejected("Тренировка сохраняется. Дождитесь завершения.")),
                            domainResults,
                        )
                    },
                    {
                        val notifications = replies.map { it.text } + bot.editedMessages.map { it.text }
                        assertEquals(listOf("Тренировка сохраняется. Дождитесь завершения."), notifications)
                    },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                    { assertFalse("Создание тренировки отменено." in replies.map { it.text }) },
                    { assertTrue(bot.apiCalls.drop(callCount).isNotEmpty()) },
                )
            }
        }

    @ParameterizedTest(name = "cancel in {0}")
    @MethodSource("waitingStates")
    fun `cancel works in every user waiting state`(
        state: TrainingFlowState,
        inputs: List<String>,
    ) = runBlocking {
        createBot().use { bot ->
            bot.sendCommand("/training_add", userId.value)
            enterInputs(bot, inputs)
            assertEquals(state, storage.get(userId)?.state)
            val registered = assertNotNull(messages.get(userId))

            assertCancelled(bot, registered)
        }
    }

    @Test
    fun `cancel discards the draft and next training has new identities and rejects old callbacks`() =
        runBlocking {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                enterInputs(bot, DATE_TIME_INPUTS)
                val oldSession = assertNotNull(storage.get(userId))
                val oldMessage = assertNotNull(messages.get(userId))
                val oldQuestion = bot.currentMessage(oldMessage.messageId)
                val oldCallback = bot.captureCallback(oldQuestion, "Нет", userId)
                enterInputs(bot, listOf("Да", "Armbar"))
                assertEquals(WaitingAddTechniqueDescription(DETAILS, "Armbar"), storage.get(userId)?.state)

                assertCancelled(bot, oldMessage)

                val newQuestion = bot.sendCommand("/training_add", userId.value, 2_829L)
                val newSession = assertNotNull(storage.get(userId))
                val newMessage = assertNotNull(messages.get(userId))
                assertNotEquals(oldSession.sessionId, newSession.sessionId)
                assertNotEquals(oldMessage.messageId, newMessage.messageId)
                assertEquals(WaitingDate, newSession.state)
                assertEquals(newSession.sessionId, newMessage.sessionId)
                assertEquals(TelegramMessageId(newQuestion.messageId), newMessage.messageId)
                assertEquals(chatId, newMessage.chatId)
                assertEquals(
                    listOf(EXPECTED_CANCEL_ROW),
                    bot.currentMessage(newMessage.messageId).withoutQuestionToken().inlineKeyboard,
                )
                enterInputs(bot, listOf("13.10.2026", "7:45"))
                val expected =
                    newSession.copy(
                        state =
                            WaitingAddTechnique(
                                TrainingDetails(LocalDate.of(2026, 10, 13), LocalTime.of(7, 45)),
                            ),
                    )
                assertEquals(expected, storage.get(userId))
                val currentQuestion = bot.currentMessage(newMessage.messageId)
                val currentCallback = bot.captureCallback(currentQuestion, "Нет", userId)
                assertNotEquals(oldCallback.data, currentCallback.data)
                val callCount = bot.apiCalls.size

                val stale = bot.deliverCallbackAndCollectReplies(oldCallback)

                assertAll(
                    { assertEquals(expected, storage.get(userId)) },
                    { assertEquals(newMessage, messages.get(userId)) },
                    { assertEquals(currentQuestion, bot.currentMessage(newMessage.messageId)) },
                    { assertEquals(emptyList(), stale.replies) },
                    { assertTrue(stale.acknowledged) },
                    { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(callCount)) },
                    { assertEquals(2, bot.apiCalls.count { it == "sendMessage" }) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    private suspend fun assertCancelled(
        bot: TestTelegramBot,
        registered: TrainingCreationMessageSession,
    ) {
        val current = bot.currentMessage(registered.messageId)
        val callbackData =
            current.inlineKeyboard
                .flatten()
                .firstOrNull()
                ?.callbackData
        val callCount = bot.apiCalls.size
        val editCount = bot.editedMessages.size
        val deletionCount = bot.deletedMessages.size
        val sendCount = bot.apiCalls.count { it == "sendMessage" }

        val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

        val cancelled = EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено.")
        assertAll(
            { assertNull(storage.get(userId)) },
            { assertNull(messages.get(userId)) },
            { assertEquals(cancelled, bot.currentMessage(registered.messageId)) },
            { assertEquals(listOf(cancelled), bot.editedMessages.drop(editCount)) },
            { assertEquals(emptyList(), replies) },
            { assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callCount)) },
            { assertEquals(sendCount, bot.apiCalls.count { it == "sendMessage" }) },
            {
                assertEquals(
                    listOf(DeletedTelegramMessage(chatId.value, cancelMessageId.value)),
                    bot.deletedMessages.drop(deletionCount),
                )
            },
            { assertTrue(repository.saveCalls.isEmpty()) },
            {
                if (callbackData != null) {
                    assertFalse(
                        messages.consumeQuestion(userId, registered, callbackData),
                        "Cancelled question is cleared",
                    )
                }
            },
        )
    }

    private suspend fun enterInputs(
        bot: TestTelegramBot,
        inputs: List<String>,
    ) {
        for (input in inputs) {
            if (input == "Да" || input == "Нет") {
                val question = bot.currentMessage(assertNotNull(messages.get(userId)).messageId)
                val interaction = bot.pressInlineButtonAndCollectReplies(question, input, userId.value)
                assertTrue(interaction.acknowledged)
                assertEquals(emptyList(), interaction.replies)
            } else {
                assertEquals(emptyList(), bot.sendTextAndCollectReplies(input, userId.value))
            }
        }
    }

    private fun createBot(service: FlowHandler = TrainingFlowService(repository)): TestTelegramBot {
        val observedService =
            object : FlowHandler by service {
                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ): FlowResult {
                    domainEvents += event
                    return service.handle(state, event).also { domainResults += it }
                }
            }
        val manager = FlowSessionManager(storage, listOf(observedService))
        return TestTelegramBot(
            telegramBot =
                TelegramBot(
                    TelegramProperties("test-token"),
                    storage,
                    manager,
                    messages,
                    queryService = TrainingQueryService(repository),
                ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )
    }

    companion object {
        @JvmStatic
        fun waitingStates(): List<Arguments> =
            listOf(
                Arguments.of(WaitingDate, emptyList<String>()),
                Arguments.of(WaitingStartTime(DATE), listOf("12.10.2026")),
                Arguments.of(WaitingAddTechnique(DETAILS), DATE_TIME_INPUTS),
                Arguments.of(WaitingTechniqueName(DETAILS), DATE_TIME_INPUTS + "Да"),
                Arguments.of(WaitingAddTechniqueDescription(DETAILS, "Armbar"), NAME_INPUTS),
                Arguments.of(WaitingTechniqueDescription(DETAILS, "Armbar"), DESCRIPTION_INPUTS),
                Arguments.of(WaitingAddAnotherTechnique(WITH_TECHNIQUE), ANOTHER_INPUTS),
                Arguments.of(WaitingAddNote(WITH_TECHNIQUE), NOTE_CHOICE_INPUTS),
                Arguments.of(WaitingNote(WITH_TECHNIQUE), NOTE_INPUTS),
                Arguments.of(
                    WaitingConfirmation(WITH_TECHNIQUE.copy(note = "Личная тренировка")),
                    NOTE_INPUTS + "Личная тренировка",
                ),
            )
    }
}
