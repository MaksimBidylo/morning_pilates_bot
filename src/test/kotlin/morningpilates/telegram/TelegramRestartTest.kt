package morningpilates.telegram

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RESTART_DETAILS = TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30))
private val NO_ACTIVE_ACTION_MENU =
    """
    Сейчас нет активного действия.

    Выберите, что хотите сделать:

    /training_add — записать тренировку
    """.trimIndent()
private val STALE_WITHOUT_SESSION = "⚠️ Это сообщение больше неактуально.\n\n$NO_ACTIVE_ACTION_MENU"
private const val STALE_WITH_ACTIVE_SESSION =
    "⚠️ Это сообщение больше неактуально.\n\nПродолжите работу в актуальном диалоге."

internal class TelegramRestartTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val newCommandId = TelegramMessageId(2_713L)
    private val newDateId = TelegramMessageId(2_829L)
    private val newTimeId = TelegramMessageId(2_947L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val events = mutableListOf<FlowEvent>()

    @ParameterizedTest(name = "old {0} button after restart displays the inactive action menu")
    @ValueSource(strings = ["Да", "Нет", "Отменить тренировку"])
    fun `old button after restart marks its message stale and permits a new training`(button: String) =
        runBlocking<Unit> {
            val previous = previousDialogue(button)
            restartedBot(previous).use { bot ->
                assertTrue(bot.apiCalls.isEmpty())
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertEquals(previous.question, bot.currentMessage(previous.question.messageId))

                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                val expected = EditedTelegramMessage(chatId, previous.question.messageId, STALE_WITHOUT_SESSION)
                assertAll(
                    { assertTrue(interaction.acknowledged) },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(events.isEmpty()) },
                    { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls) },
                    { assertEquals(listOf(expected), bot.editedMessages) },
                    { assertEquals(expected, bot.currentMessage(previous.question.messageId)) },
                    { assertTrue(bot.deleteMessageAttempts.isEmpty()) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )

                bot.sendCommand("/training_add", userId.value, newCommandId.value)
                val next = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                assertEquals(WaitingDate, next.state)
                assertNotEquals(previous.session.sessionId, next.sessionId)
                assertNotEquals(previous.question.messageId, registered.messageId)
                assertEquals(next.sessionId, registered.sessionId)
                assertNotNull(messages.questionToken(userId))
                assertTrue(events.isEmpty())
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @Test
    fun `old message after restart is marked stale while an identical new question keeps working`() =
        runBlocking<Unit> {
            val previous = previousDialogue()
            restartedBot(previous).use { bot ->
                val registered = startNewTraining(bot)
                val current = bot.currentMessage(registered.messageId)
                val session = assertNotNull(storage.get(userId))
                assertEquals(previous.question.text, current.text)
                assertNotEquals(previous.question.messageId, current.messageId)
                assertNotEquals(previous.session.sessionId, session.sessionId)
                val token = messages.questionToken(userId)
                val beforeEvents = events.toList()
                val beforeDeletions = bot.deletedMessages
                val calls = bot.apiCalls.size
                val expected = EditedTelegramMessage(chatId, previous.question.messageId, STALE_WITH_ACTIVE_SESSION)

                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                assertAll(
                    { assertTrue(interaction.acknowledged) },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registered, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(beforeEvents, events) },
                    { assertEquals(current, bot.currentMessage(registered.messageId)) },
                    { assertEquals(expected, bot.currentMessage(previous.question.messageId)) },
                    { assertEquals(expected, bot.editedMessages.last()) },
                    { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(calls)) },
                    { assertEquals(beforeDeletions, bot.deletedMessages) },
                    { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                val continued = bot.pressInlineButtonAndCollectReplies(current, "Да", userId.value)
                assertTrue(continued.acknowledged)
                assertTrue(continued.replies.isEmpty())
                assertEquals(session.copy(state = WaitingTechniqueName(RESTART_DETAILS)), storage.get(userId))
                assertEquals(registered, messages.get(userId))
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "Да")), events.drop(beforeEvents.size))
                assertEquals("Введите название техники.", bot.currentMessage(registered.messageId).text)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `text after restart shows the neutral action menu without deleting input or starting a flow`() =
        runBlocking<Unit> {
            val previous = previousDialogue()
            restartedBot(previous).use { bot ->
                val replies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, newDateId.value)

                assertAll(
                    { assertEquals(listOf(SentTelegramMessage(chatId.value, NO_ACTIVE_ACTION_MENU)), replies) },
                    { assertEquals(listOf("sendMessage"), bot.apiCalls) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(events.isEmpty()) },
                    { assertTrue(bot.deleteMessageAttempts.isEmpty()) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                    { assertEquals(previous.question, bot.currentMessage(previous.question.messageId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @ParameterizedTest(name = "failed stale message edit with a new session = {0}")
    @ValueSource(booleans = [false, true])
    fun `failed stale message edit changes no domain data and sends no replacement`(startNewSession: Boolean) =
        runBlocking<Unit> {
            val previous = previousDialogue()
            restartedBot(previous).use { bot ->
                if (startNewSession) startNewTraining(bot)
                val session = storage.get(userId)
                val registered = messages.get(userId)
                val current = registered?.let { bot.currentMessage(it.messageId) }
                val token = messages.questionToken(userId)
                val beforeEvents = events.toList()
                val beforeDeletions = bot.deletedMessages
                val calls = bot.apiCalls.size
                val sendCount = bot.apiCalls.count { it == "sendMessage" }
                val editCount = bot.editedMessages.size
                bot.failNextEditMessageText("Bad Request: message to edit not found")

                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                val expectedText = if (startNewSession) STALE_WITH_ACTIVE_SESSION else STALE_WITHOUT_SESSION
                assertAll(
                    { assertTrue(interaction.acknowledged) },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registered, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(beforeEvents, events) },
                    { assertEquals(current, registered?.let { bot.currentMessage(it.messageId) }) },
                    { assertEquals(previous.question, bot.currentMessage(previous.question.messageId)) },
                    { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(calls)) },
                    {
                        assertEquals(
                            listOf(EditedTelegramMessage(chatId, previous.question.messageId, expectedText)),
                            bot.editedMessages.drop(editCount),
                        )
                    },
                    { assertEquals(beforeDeletions, bot.deletedMessages) },
                    { assertEquals(sendCount, bot.apiCalls.count { it == "sendMessage" }) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    private suspend fun previousDialogue(button: String = "Да"): PreviousDialogue {
        val previousStorage = InMemoryFlowStorage()
        val previousMessages = TrainingMessageSessionStorage()
        val previousRepository = FakeTrainingRepository()
        val manager = FlowSessionManager(previousStorage, listOf(TrainingFlowService(previousRepository)))
        TestTelegramBot(
            TelegramBot(TelegramProperties("test-token"), previousStorage, manager, previousMessages),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        ).use { bot ->
            bot.sendCommand("/training_add", userId.value, 713L)
            bot.sendTextAndCollectReplies("12.10.2026", userId.value, 829L)
            bot.sendTextAndCollectReplies("6:30", userId.value, 947L)
            val session = assertNotNull(previousStorage.get(userId))
            assertEquals(WaitingAddTechnique(RESTART_DETAILS), session.state)
            val registered = assertNotNull(previousMessages.get(userId))
            val question = bot.currentMessage(registered.messageId)
            assertTrue(previousRepository.saveCalls.isEmpty())
            return PreviousDialogue(session, question, bot.captureCallback(question, button, userId))
        }
    }

    private fun restartedBot(previous: PreviousDialogue): TestTelegramBot {
        assertNull(storage.get(userId))
        assertNull(messages.get(userId))
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
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages),
            chatId = chatId.value,
            firstBotMessageId = 8_351L,
            existingMessages = listOf(previous.question),
        )
    }

    private suspend fun startNewTraining(bot: TestTelegramBot): TrainingMessageSession {
        bot.sendCommand("/training_add", userId.value, newCommandId.value)
        bot.sendTextAndCollectReplies("12.10.2026", userId.value, newDateId.value)
        bot.sendTextAndCollectReplies("6:30", userId.value, newTimeId.value)
        assertEquals(WaitingAddTechnique(RESTART_DETAILS), storage.get(userId)?.state)
        return assertNotNull(messages.get(userId))
    }
}

private data class PreviousDialogue(
    val session: UserSession,
    val question: EditedTelegramMessage,
    val callback: CallbackQuery,
)
