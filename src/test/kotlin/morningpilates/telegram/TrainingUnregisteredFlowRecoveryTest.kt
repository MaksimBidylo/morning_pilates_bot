package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.training.populatedTrainingDetails
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UNREGISTERED_DATE = LocalDate.of(2026, 10, 12)
private val UNREGISTERED_DETAILS = populatedTrainingDetails()
private val DATE_QUESTION =
    CallbackQuestion(
        """
        Введите дату тренировки.

        Например: 11.10.2026 или 2026-10-11.

        Можно использовать разделители: точку, дефис или косую черту.
        """.trimIndent(),
    )
private const val INVALID_DATE_REASON =
    "Некорректная дата. Используйте формат ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. " +
        "Разделители: ., / или -. Проверьте правильность даты."
private val DATE_ERROR_QUESTION = CallbackQuestion("⚠️ $INVALID_DATE_REASON\n\n${DATE_QUESTION.text}")
private val TIME_QUESTION =
    CallbackQuestion(
        """
        Введите время начала тренировки.

        Например: 8:00, 19:30 или 8-00.
        """.trimIndent(),
    )

internal class TrainingUnregisteredFlowRecoveryTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val commandId = TelegramMessageId(713L)
    private val dateMessageId = TelegramMessageId(829L)
    private val recoveryCommandId = TelegramMessageId(2_713L)
    private val continuationMessageId = TelegramMessageId(947L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val events = mutableListOf<FlowEvent>()

    @Test
    fun `invalid date after failed initial send registers error question and correction edits it`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val original = failInitialQuestion(bot)
                val calls = bot.apiCalls.size

                val replies = bot.sendTextAndCollectReplies("31.02.2026", userId.value, dateMessageId.value)

                val registered = assertNotNull(messages.get(userId))
                assertAll(
                    { assertEquals(original, storage.get(userId)) },
                    { assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "31.02.2026")), events) },
                    { assertEquals(listOf(DATE_ERROR_QUESTION.text), replies.map { it.text }) },
                    { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(calls)) },
                    {
                        assertEquals(
                            TrainingMessageSession(
                                original.sessionId,
                                chatId,
                                TelegramMessageId(replies.single().messageId),
                            ),
                            registered,
                        )
                    },
                    {
                        assertEquals(
                            DATE_ERROR_QUESTION.at(registered),
                            bot.currentMessage(registered.messageId).withoutQuestionToken(),
                        )
                    },
                    { assertNotNull(messages.questionToken(userId)) },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, dateMessageId.value)),
                            bot.deletedMessages,
                        )
                    },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                continueAfterRecovery(
                    bot,
                    original,
                    registered,
                    UnregisteredRecoveryScenario(
                        WaitingDate,
                        DATE_QUESTION,
                        "12.10.2026",
                        WaitingStartTime(UNREGISTERED_DATE),
                        TIME_QUESTION,
                    ),
                )
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @Test
    fun `failed invalid date recovery keeps waiting date and command restores it without replaying input`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val original = failInitialQuestion(bot)
                val calls = bot.apiCalls.size
                bot.failNextSendMessage("Bad Request: chat not found")

                val replies = bot.sendTextAndCollectReplies("31.02.2026", userId.value, dateMessageId.value)

                assertEquals(original, storage.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "31.02.2026")), events)
                assertEquals(listOf("sendMessage"), bot.apiCalls.drop(calls))
                assertTrue(replies.isEmpty())
                assertTrue(bot.deletedMessages.isEmpty())
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertTrue(repository.saveCalls.isEmpty())

                restoreByCommand(bot, original, DATE_QUESTION)
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "31.02.2026")), events)
            }
        }

    @Test
    fun `date after failed initial send registers its time question and time input edits it`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val original = failInitialQuestion(bot)
                val calls = bot.apiCalls.size
                val input: FlowEvent = FlowEvent.UserInput(userId, "12.10.2026")

                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                assertAll(
                    { assertEquals(original.copy(state = WaitingStartTime(UNREGISTERED_DATE)), storage.get(userId)) },
                    { assertEquals(listOf(input), events) },
                    { assertEquals(listOf(sentTrainingQuestion(chatId.value, TIME_QUESTION.text)), replies) },
                    { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(calls)) },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, dateMessageId.value)),
                            bot.deletedMessages,
                        )
                    },
                    {
                        assertEquals(
                            TrainingMessageSession(
                                original.sessionId,
                                chatId,
                                TelegramMessageId(replies.single().messageId),
                            ),
                            messages.get(userId),
                        )
                    },
                    { assertNotNull(messages.questionToken(userId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                val registered = assertNotNull(messages.get(userId))
                assertEquals(
                    TIME_QUESTION.at(registered),
                    bot.currentMessage(registered.messageId).withoutQuestionToken(),
                )
                continueAfterRecovery(bot, assertNotNull(storage.get(userId)), registered, timeScenario())
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @ParameterizedTest(name = "recover unregistered {0}")
    @MethodSource("waitingStates")
    fun `command restores waiting state and flow continues`(scenario: UnregisteredRecoveryScenario) =
        runBlocking<Unit> {
            val original = UserSession(scenario.state, Instant.parse("2026-10-12T03:15:00Z"))
            check(storage.start(userId, original))
            createBot().use { bot ->
                val registered = restoreByCommand(bot, original, scenario.question)

                continueAfterRecovery(bot, original, registered, scenario)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @Test
    fun `failed text recovery preserves the accepted date and a later command recovers without replaying it`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val original = failInitialQuestion(bot)
                val calls = bot.apiCalls.size
                bot.failNextSendMessage("Bad Request: chat not found")

                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                val pending = original.copy(state = WaitingStartTime(UNREGISTERED_DATE))
                assertEquals(pending, storage.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "12.10.2026")), events)
                assertEquals(listOf("sendMessage"), bot.apiCalls.drop(calls))
                assertTrue(replies.isEmpty())
                assertTrue(bot.deletedMessages.isEmpty())
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertTrue(repository.saveCalls.isEmpty())

                val registered = restoreByCommand(bot, pending, TIME_QUESTION)
                continueAfterRecovery(bot, pending, registered, timeScenario())
            }
        }

    @Test
    fun `failed command recovery keeps all existing details and the next command can recover`() =
        runBlocking<Unit> {
            val scenario = nameScenario()
            val original = UserSession(scenario.state, Instant.parse("2026-10-12T03:15:00Z"))
            check(storage.start(userId, original))
            createBot().use { bot ->
                bot.failNextSendMessage("Bad Request: chat not found")

                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, commandId.value)

                assertEquals(original, storage.get(userId))
                assertTrue(events.isEmpty())
                assertTrue(replies.isEmpty())
                assertEquals(listOf("sendMessage"), bot.apiCalls)
                assertTrue(bot.deletedMessages.isEmpty())
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertTrue(repository.saveCalls.isEmpty())

                val registered = restoreByCommand(bot, original, scenario.question)
                continueAfterRecovery(bot, original, registered, scenario)
            }
        }

    private suspend fun restoreByCommand(
        bot: TestTelegramBot,
        original: UserSession,
        question: CallbackQuestion,
    ): TrainingMessageSession {
        val eventCount = events.size
        val calls = bot.apiCalls.size
        val deletions = bot.deletedMessages

        val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, recoveryCommandId.value)

        assertAll(
            { assertEquals(original, storage.get(userId)) },
            { assertEquals(eventCount, events.size) },
            { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(calls)) },
            {
                assertEquals(
                    deletions + DeletedTelegramMessage(chatId.value, recoveryCommandId.value),
                    bot.deletedMessages,
                )
            },
            { assertEquals(listOf(question.text), replies.map { it.text }) },
            { assertNotNull(messages.get(userId)) },
            { assertNotNull(messages.questionToken(userId)) },
            { assertTrue(repository.saveCalls.isEmpty()) },
        )
        val registered = assertNotNull(messages.get(userId))
        assertEquals(
            TrainingMessageSession(original.sessionId, chatId, TelegramMessageId(replies.single().messageId)),
            registered,
        )
        assertEquals(question.at(registered), bot.currentMessage(registered.messageId).withoutQuestionToken())
        return registered
    }

    private suspend fun continueAfterRecovery(
        bot: TestTelegramBot,
        original: UserSession,
        registered: TrainingMessageSession,
        scenario: UnregisteredRecoveryScenario,
    ) {
        val eventCount = events.size
        val calls = bot.apiCalls.size
        val editCount = bot.editedMessages.size
        if (scenario.question.callbackAction == null) {
            val replies = bot.sendTextAndCollectReplies(scenario.input, userId.value, continuationMessageId.value)
            assertTrue(replies.isEmpty())
            assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(calls))
            assertEquals(DeletedTelegramMessage(chatId.value, continuationMessageId.value), bot.deletedMessages.last())
        } else {
            val result =
                bot.pressInlineButtonAndCollectReplies(
                    bot.currentMessage(registered.messageId),
                    scenario.input,
                    userId.value,
                )
            assertTrue(result.acknowledged)
            assertTrue(result.replies.isEmpty())
            assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(calls))
        }
        assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, scenario.input)), events.drop(eventCount))
        val nextState = scenario.nextState
        val expected =
            if (nextState == null) {
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено.")
            } else {
                assertEquals(original.copy(state = nextState), storage.get(userId))
                assertEquals(registered, messages.get(userId))
                assertNotNull(scenario.nextQuestion).at(registered)
            }
        assertEquals(listOf(expected), bot.editedMessages.drop(editCount).map { it.withoutQuestionToken() })
        assertEquals(expected, bot.currentMessage(registered.messageId).withoutQuestionToken())
        assertTrue(repository.saveCalls.isEmpty())
    }

    private suspend fun failInitialQuestion(bot: TestTelegramBot): UserSession {
        bot.failNextSendMessage("Bad Request: chat not found")
        assertTrue(bot.sendCommandAndCollectReplies("/training_add", userId.value, commandId.value).isEmpty())
        val session = assertNotNull(storage.get(userId))
        assertEquals(WaitingDate, session.state)
        assertNull(messages.get(userId))
        assertNull(messages.questionToken(userId))
        assertTrue(events.isEmpty())
        assertTrue(bot.deletedMessages.isEmpty())
        return session
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
        return TestTelegramBot(
            TelegramBot(
                TelegramProperties("test-token"),
                storage,
                FlowSessionManager(storage, listOf(observed)),
                messages,
                queryService = TrainingQueryService(repository),
            ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )
    }

    companion object {
        @JvmStatic
        fun waitingStates(): List<UnregisteredRecoveryScenario> =
            listOf(
                timeScenario(),
                nameScenario(),
                UnregisteredRecoveryScenario(
                    WaitingAddTechniqueDescription(UNREGISTERED_DETAILS, "Kimura"),
                    CallbackQuestion("Добавить описание техники?", "add_technique_description"),
                    "Нет",
                    WaitingAddAnotherTechnique(
                        UNREGISTERED_DETAILS.copy(
                            techniques = UNREGISTERED_DETAILS.techniques + TechniqueDraft("Kimura"),
                        ),
                    ),
                    CallbackQuestion("Добавить ещё одну технику?", "add_another_technique"),
                ),
                UnregisteredRecoveryScenario(
                    WaitingConfirmation(UNREGISTERED_DETAILS),
                    CallbackQuestion("Сохранить тренировку?", "confirmation"),
                    "Нет",
                    null,
                    null,
                ),
            )
    }
}

private fun timeScenario() =
    UnregisteredRecoveryScenario(
        WaitingStartTime(UNREGISTERED_DATE),
        TIME_QUESTION,
        "6:30",
        WaitingAddTechnique(TrainingDetails(UNREGISTERED_DATE, LocalTime.of(6, 30))),
        CallbackQuestion("Добавить технику?", "add_technique"),
    )

private fun nameScenario() =
    UnregisteredRecoveryScenario(
        WaitingTechniqueName(UNREGISTERED_DETAILS),
        CallbackQuestion("Введите название техники."),
        "Kimura",
        WaitingAddTechniqueDescription(UNREGISTERED_DETAILS, "Kimura"),
        CallbackQuestion("Добавить описание техники?", "add_technique_description"),
    )

internal data class UnregisteredRecoveryScenario(
    val state: TrainingFlowState,
    val question: CallbackQuestion,
    val input: String,
    val nextState: TrainingFlowState?,
    val nextQuestion: CallbackQuestion?,
) {
    override fun toString(): String = state.javaClass.simpleName
}
