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
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
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

private const val STALE_CALLBACK_NOTICE = "Этот диалог больше неактуален. Используйте меню команд."
private val RESTART_DETAILS = TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30))
private val NO_ACTIVE_ACTION_MENU =
    """
    Сейчас нет активного действия.

    Выберите, что хотите сделать:

    /training_add — записать тренировку
    """.trimIndent()

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

    @ParameterizedTest(name = "old {0} button after restart is only acknowledged")
    @ValueSource(strings = ["Да", "Нет", "Отменить тренировку"])
    fun `old button after restart is ignored and permits a new training`(button: String) =
        runBlocking<Unit> {
            val previous = previousDialogue(button)
            restartedBot(previous).use { bot ->
                assertTrue(bot.apiCalls.isEmpty())
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertEquals(previous.message, bot.currentMessage(previous.message.messageId))

                val answersBefore = bot.answerCallbackQueryRequests.size
                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                assertAll(
                    { assertTrue(interaction.acknowledged) },
                    {
                        assertEquals(
                            listOf(TelegramCallbackAnswer(previous.callback.id, STALE_CALLBACK_NOTICE)),
                            bot.answerCallbackQueryRequests.drop(answersBefore),
                        )
                    },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(events.isEmpty()) },
                    { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                    { assertEquals(previous.message, bot.currentMessage(previous.message.messageId)) },
                    { assertTrue(bot.deleteMessageAttempts.isEmpty()) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )

                bot.sendCommand("/training_add", userId.value, newCommandId.value)
                val next = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                assertEquals(WaitingDate, next.state)
                assertNotEquals(previous.session.sessionId, next.sessionId)
                assertNotEquals(previous.message.messageId, registered.messageId)
                assertEquals(next.sessionId, registered.sessionId)
                assertNotNull(messages.questionToken(userId))
                assertTrue(events.isEmpty())
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @Test
    fun `old callback after restart leaves both messages intact while an identical new question keeps working`() =
        runBlocking<Unit> {
            val previous = previousDialogue()
            restartedBot(previous).use { bot ->
                val registered = startNewTraining(bot)
                val current = bot.currentMessage(registered.messageId)
                val session = assertNotNull(storage.get(userId))
                assertEquals(previous.message.text, current.text)
                assertNotEquals(previous.message.messageId, current.messageId)
                assertNotEquals(previous.session.sessionId, session.sessionId)
                val token = messages.questionToken(userId)
                val beforeEvents = events.toList()
                val beforeDeletions = bot.deletedMessages
                val calls = bot.apiCalls.size
                val beforeEdits = bot.editedMessages

                val answersBefore = bot.answerCallbackQueryRequests.size
                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                assertAll(
                    { assertTrue(interaction.acknowledged) },
                    {
                        assertEquals(
                            listOf(TelegramCallbackAnswer(previous.callback.id, STALE_CALLBACK_NOTICE)),
                            bot.answerCallbackQueryRequests.drop(answersBefore),
                        )
                    },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registered, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(beforeEvents, events) },
                    { assertEquals(current, bot.currentMessage(registered.messageId)) },
                    { assertEquals(previous.message, bot.currentMessage(previous.message.messageId)) },
                    { assertEquals(beforeEdits, bot.editedMessages) },
                    { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls)) },
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
                    { assertEquals(previous.message, bot.currentMessage(previous.message.messageId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @Test
    fun `delayed confirmation after restart cannot overwrite the saved HTML card`() =
        runBlocking<Unit> {
            val previous = previousSavedDialogue()
            val savedCalls = repository.saveCalls.toList()
            restartedBot(previous).use { bot ->
                assertEquals(previous.message, bot.currentMessage(previous.message.messageId))
                assertTrue(bot.apiCalls.isEmpty())

                val answersBefore = bot.answerCallbackQueryRequests.size
                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                assertAll(
                    { assertTrue(interaction.acknowledged) },
                    {
                        assertEquals(
                            listOf(TelegramCallbackAnswer(previous.callback.id, STALE_CALLBACK_NOTICE)),
                            bot.answerCallbackQueryRequests.drop(answersBefore),
                        )
                    },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                    { assertTrue(bot.deleteMessageAttempts.isEmpty()) },
                    { assertEquals(previous.message, bot.currentMessage(previous.message.messageId)) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(events.isEmpty()) },
                    { assertEquals(savedCalls, repository.saveCalls) },
                )
            }
        }

    @ParameterizedTest(name = "current {0} button has one acknowledgment without a stale notice")
    @ValueSource(strings = ["Да", "Нет", "Отменить тренировку"])
    fun `current launch buttons have one acknowledgment without the stale notice`(button: String) =
        runBlocking<Unit> {
            restartedBot(previousDialogue()).use { bot ->
                val registered = startNewTraining(bot)
                val question = bot.currentMessage(registered.messageId)
                val callback = bot.captureCallback(question, button, userId)
                val answersBefore = bot.answerCallbackQueryRequests.size
                val callsBefore = bot.apiCalls.size

                val interaction = bot.deliverCallbackAndCollectReplies(callback)

                assertTrue(interaction.acknowledged)
                assertTrue(interaction.replies.isEmpty())
                assertEquals(
                    listOf(TelegramCallbackAnswer(callback.id, null)),
                    bot.answerCallbackQueryRequests.drop(answersBefore),
                )
                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callsBefore))
            }
        }

    private suspend fun previousSavedDialogue(): PreviousDialogue {
        val previousStorage = InMemoryFlowStorage()
        val previousMessages = TrainingMessageSessionStorage()
        val manager = FlowSessionManager(previousStorage, listOf(TrainingFlowService(repository)))
        TestTelegramBot(
            TelegramBot(
                TelegramProperties("test-token"),
                previousStorage,
                manager,
                previousMessages,
                queryService = TrainingQueryService(repository),
            ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        ).use { bot ->
            bot.sendCommand("/training_add", userId.value, 713L)
            bot.sendTextAndCollectReplies("12.10.2026", userId.value, 829L)
            bot.sendTextAndCollectReplies("6:30", userId.value, 947L)
            val registered = assertNotNull(previousMessages.get(userId))
            bot.pressInlineButtonAndCollectReplies(bot.currentMessage(registered.messageId), "Да", userId.value)
            bot.sendTextAndCollectReplies("Armbar", userId.value, 1_059L)
            repeat(3) {
                bot.pressInlineButtonAndCollectReplies(bot.currentMessage(registered.messageId), "Нет", userId.value)
            }
            val details = RESTART_DETAILS.copy(techniques = listOf(TechniqueDraft("Armbar")))
            val session = assertNotNull(previousStorage.get(userId))
            assertEquals(WaitingConfirmation(details), session.state)
            val question = bot.currentMessage(registered.messageId)
            assertEquals("Сохранить тренировку?", question.text)
            val callback = bot.captureCallback(question, "Да", userId)
            val answersBefore = bot.answerCallbackQueryRequests.size
            assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
            assertEquals(
                listOf(TelegramCallbackAnswer(callback.id, null)),
                bot.answerCallbackQueryRequests.drop(answersBefore),
            )
            assertEquals(details, repository.saveCalls.single().details)
            assertNull(previousStorage.get(userId))
            assertNull(previousMessages.get(userId))
            assertNull(previousMessages.questionToken(userId))
            val saved = bot.currentMessage(registered.messageId)
            assertEquals(
                EditedTelegramMessage(
                    chatId,
                    registered.messageId,
                    "Тренировка сохранена.\n\n<blockquote expandable>Дата: 12.10.2026\n" +
                        "Время: 06:30\n\nТехники:\n1. Armbar</blockquote>",
                    parseMode = "HTML",
                ),
                saved,
            )
            assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
            return PreviousDialogue(session, saved, callback)
        }
    }

    private suspend fun previousDialogue(button: String = "Да"): PreviousDialogue {
        val previousStorage = InMemoryFlowStorage()
        val previousMessages = TrainingMessageSessionStorage()
        val previousRepository = FakeTrainingRepository()
        val manager = FlowSessionManager(previousStorage, listOf(TrainingFlowService(previousRepository)))
        TestTelegramBot(
            TelegramBot(
                TelegramProperties("test-token"),
                previousStorage,
                manager,
                previousMessages,
                queryService = TrainingQueryService(previousRepository),
            ),
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
            TelegramBot(
                TelegramProperties("test-token"),
                storage,
                manager,
                messages,
                queryService = TrainingQueryService(repository),
            ),
            chatId = chatId.value,
            firstBotMessageId = 8_351L,
            existingMessages = listOf(previous.message),
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
    val message: EditedTelegramMessage,
    val callback: CallbackQuery,
)
