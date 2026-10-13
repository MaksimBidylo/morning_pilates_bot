package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DELETE_ERROR = "Bad Request: message can't be deleted"
private val DELETE_TEST_DATE = LocalDate.of(2026, 10, 12)
private val DELETE_TEST_DETAILS = TrainingDetails(DELETE_TEST_DATE, LocalTime.of(6, 30))
private val DATE_QUESTION =
    CallbackQuestion(
        """
        Введите дату тренировки.

        Например: 11.10.2026 или 2026-10-11.

        Можно использовать разделители: точку, дефис или косую черту.
        """.trimIndent(),
    )
private val TIME_QUESTION =
    CallbackQuestion(
        """
        Введите время начала тренировки.

        Например: 8:00, 19:30 или 8-00.
        """.trimIndent(),
    )
private val TECHNIQUE_QUESTION = CallbackQuestion("Добавить технику?", "add_technique")

internal class TrainingDeleteFailureTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val commandId = TelegramMessageId(713L)
    private val dateMessageId = TelegramMessageId(829L)
    private val timeMessageId = TelegramMessageId(947L)
    private val repeatCommandId = TelegramMessageId(2_713L)
    private val cancelCommandId = TelegramMessageId(2_829L)
    private val nextCommandId = TelegramMessageId(2_947L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val events = mutableListOf<FlowEvent>()

    @Test
    fun `failed deletion of date input preserves its transition and time input continues normally`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, commandId.value)
                val original = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val successfulDeletions = bot.deletedMessages
                val calls = bot.apiCalls.size
                bot.failNextDeleteMessage(DELETE_ERROR)

                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                assertTrue(replies.isEmpty())
                assertEquals(original.copy(state = WaitingStartTime(DELETE_TEST_DATE)), storage.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "12.10.2026")), events)
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(calls))
                assertQuestion(bot, registered, TIME_QUESTION)
                assertFailedDeletion(bot, dateMessageId, successfulDeletions)
                val callsBeforeTime = bot.apiCalls.size

                val timeReplies = bot.sendTextAndCollectReplies("6:30", userId.value, timeMessageId.value)

                assertTrue(timeReplies.isEmpty())
                assertEquals(original.copy(state = WaitingAddTechnique(DELETE_TEST_DETAILS)), storage.get(userId))
                assertEquals(
                    listOf<FlowEvent>(FlowEvent.UserInput(userId, "12.10.2026"), FlowEvent.UserInput(userId, "6:30")),
                    events,
                )
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callsBeforeTime))
                assertQuestion(bot, registered, TECHNIQUE_QUESTION)
                assertSuccessfulDeletion(bot, timeMessageId)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed deletion of the first command preserves the registered date question and accepts a date`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.failNextDeleteMessage(DELETE_ERROR)

                val sent = bot.sendCommand("/training_add", userId.value, commandId.value)

                val original = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                assertEquals(WaitingDate, original.state)
                assertEquals(
                    TrainingMessageSession(original.sessionId, chatId, TelegramMessageId(sent.messageId)),
                    registered,
                )
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls)
                assertTrue(events.isEmpty())
                assertQuestion(bot, registered, DATE_QUESTION)
                assertFailedDeletion(bot, commandId, emptyList())

                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                assertTrue(replies.isEmpty())
                assertEquals(original.copy(state = WaitingStartTime(DELETE_TEST_DATE)), storage.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "12.10.2026")), events)
                assertQuestion(bot, registered, TIME_QUESTION)
                assertSuccessfulDeletion(bot, dateMessageId)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed deletion of repeated command keeps warning and the original yes callback usable`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                startAtTechniqueQuestion(bot)
                val original = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val question = assertQuestion(bot, registered, TECHNIQUE_QUESTION)
                val yes = bot.captureCallback(question, "Да", userId)
                val token = messages.questionToken(userId)
                val eventCount = events.size
                val successfulDeletions = bot.deletedMessages
                val calls = bot.apiCalls.size
                bot.failNextDeleteMessage(DELETE_ERROR)

                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatCommandId.value)

                assertTrue(replies.isEmpty())
                assertEquals(original, storage.get(userId))
                assertEquals(registered, messages.get(userId))
                assertEquals(token, messages.questionToken(userId))
                assertEquals(eventCount, events.size)
                val warning =
                    "⚠️ <b>У вас есть незавершённая тренировка</b>\n\n" +
                        "Продолжите или отмените её, прежде чем создавать новую."
                assertEquals(
                    question.copy(text = "$warning\n\n${question.text}", parseMode = "HTML"),
                    bot.currentMessage(registered.messageId),
                )
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(calls))
                assertFailedDeletion(bot, repeatCommandId, successfulDeletions)
                val callsBeforeYes = bot.apiCalls.size

                val result = bot.deliverCallbackAndCollectReplies(yes)

                assertTrue(result.acknowledged)
                assertTrue(result.replies.isEmpty())
                assertEquals(original.copy(state = WaitingTechniqueName(DELETE_TEST_DETAILS)), storage.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "Да")), events.drop(eventCount))
                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callsBeforeYes))
                assertQuestion(bot, registered, CallbackQuestion("Введите название техники."))
                assertEquals(successfulDeletions, bot.deletedMessages)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed deletion of cancel command cannot undo cancellation and the next training starts`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                startAtTechniqueQuestion(bot)
                val original = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val calls = bot.apiCalls.size
                val eventCount = events.size
                val successfulDeletions = bot.deletedMessages
                bot.failNextDeleteMessage(DELETE_ERROR)

                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelCommandId.value)

                assertTrue(replies.isEmpty())
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), events.drop(eventCount))
                assertEquals(
                    EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено."),
                    bot.currentMessage(registered.messageId),
                )
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(calls))
                assertFailedDeletion(bot, cancelCommandId, successfulDeletions)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })

                bot.sendCommand("/training_add", userId.value, nextCommandId.value)

                val next = assertNotNull(storage.get(userId))
                val nextMessage = assertNotNull(messages.get(userId))
                assertEquals(WaitingDate, next.state)
                assertNotEquals(original.sessionId, next.sessionId)
                assertNotEquals(registered.messageId, nextMessage.messageId)
                assertEquals(next.sessionId, nextMessage.sessionId)
                assertQuestion(bot, nextMessage, DATE_QUESTION)
                assertSuccessfulDeletion(bot, nextCommandId)
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), events.drop(eventCount))
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed deletion of cancel without a session preserves the response and permits starting training`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                bot.failNextDeleteMessage("Bad Request: message to delete not found")

                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelCommandId.value)

                assertEquals(listOf(SentTelegramMessage(chatId.value, "Нет активной тренировки.")), replies)
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertNull(messages.questionToken(userId))
                assertTrue(events.isEmpty())
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls)
                assertFailedDeletion(bot, cancelCommandId, emptyList())
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })

                bot.sendCommand("/training_add", userId.value, nextCommandId.value)

                val next = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                assertEquals(WaitingDate, next.state)
                assertEquals(next.sessionId, registered.sessionId)
                assertQuestion(bot, registered, DATE_QUESTION)
                assertSuccessfulDeletion(bot, nextCommandId)
                assertTrue(events.isEmpty())
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private fun assertFailedDeletion(
        bot: TestTelegramBot,
        messageId: TelegramMessageId,
        successfulBefore: List<DeletedTelegramMessage>,
    ) {
        assertEquals(TelegramDeleteAttempt(chatId, messageId, succeeded = false), bot.deleteMessageAttempts.last())
        assertEquals(1, bot.deleteMessageAttempts.count { it.messageId == messageId })
        assertEquals(successfulBefore, bot.deletedMessages)
        assertEquals("deleteMessage", bot.apiCalls.last())
    }

    private fun assertSuccessfulDeletion(
        bot: TestTelegramBot,
        messageId: TelegramMessageId,
    ) {
        assertEquals(TelegramDeleteAttempt(chatId, messageId, succeeded = true), bot.deleteMessageAttempts.last())
        assertEquals(DeletedTelegramMessage(chatId.value, messageId.value), bot.deletedMessages.last())
    }

    private fun assertQuestion(
        bot: TestTelegramBot,
        registered: TrainingMessageSession,
        expected: CallbackQuestion,
    ): EditedTelegramMessage {
        assertEquals(registered, messages.get(userId))
        val current = bot.currentMessage(registered.messageId)
        assertEquals(expected.at(registered), current.withoutQuestionToken())
        val token =
            current.inlineKeyboard
                .flatten()
                .first()
                .callbackData
                .substringAfterLast(':')
        assertEquals(token, messages.questionToken(userId))
        return current
    }

    private suspend fun startAtTechniqueQuestion(bot: TestTelegramBot) {
        bot.sendCommand("/training_add", userId.value, commandId.value)
        bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)
        bot.sendTextAndCollectReplies("6:30", userId.value, timeMessageId.value)
        assertEquals(WaitingAddTechnique(DELETE_TEST_DETAILS), storage.get(userId)?.state)
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
            ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )
    }
}
