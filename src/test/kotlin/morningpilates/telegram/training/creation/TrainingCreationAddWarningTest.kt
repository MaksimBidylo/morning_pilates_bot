package morningpilates.telegram.training.creation

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.command.CancelCommandTest
import morningpilates.telegram.support.DeletedTelegramMessage
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.trainingQuestion
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WARNING =
    "⚠️ <b>У вас есть незавершённая тренировка</b>\n\n" +
        "Продолжите или отмените её, прежде чем создавать новую."
private val DATE_TIME = listOf("12.10.2026", "6:30")

internal class TrainingCreationAddWarningTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val repeatId = TelegramMessageId(2_713L)
    private val events = mutableListOf<FlowEvent>()

    @Test
    fun `repeated command sends a new current question and preserves the complete draft`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME + listOf("Да", "Armbar", "Да", "Контроль руки", "Нет", "Да", "Лёгкая тренировка"))
                val before = assertNotNull(storage.get(userId))
                assertEquals(
                    WaitingConfirmation(
                        TrainingDetails(
                            LocalDate.of(2026, 10, 12),
                            LocalTime.of(6, 30),
                            listOf(TechniqueDraft("Armbar", "Контроль руки")),
                            "Лёгкая тренировка",
                        ),
                    ),
                    before.state,
                )
                val original = currentQuestion(bot)
                val callCount = bot.apiCalls.size
                val eventCount = events.size
                val deletionCount = bot.deletedMessages.size

                // Client-only deletion is not observable here. Edits would succeed,
                // but explicit recovery must send a new question regardless.
                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatId.value)

                assertAll(
                    { assertEquals(before, storage.get(userId)) },
                    { assertEquals(eventCount, events.size) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                    { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(callCount)) },
                    { assertEquals(1, replies.size, "Recovery must send a new question") },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, repeatId.value)),
                            bot.deletedMessages.drop(deletionCount),
                        )
                    },
                )
                val replacement = assertNotNull(messages.get(userId))
                assertEquals(before.sessionId, replacement.sessionId)
                assertEquals(chatId, replacement.chatId)
                assertNotEquals(original.messageId, replacement.messageId)
                assertEquals(TelegramMessageId(replies.single().messageId), replacement.messageId)
                assertEquals(
                    warningFor(original).copy(messageId = replacement.messageId).withoutQuestionToken(),
                    currentQuestion(bot).withoutQuestionToken(),
                )
            }
        }

    @ParameterizedTest(name = "repeated command in {0}")
    @MethodSource("morningpilates.telegram.command.CancelCommandTest#waitingStates")
    fun `repeat adds warning without changing any waiting state or keyboard`(
        state: TrainingFlowState,
        inputs: List<String>,
    ) = runBlocking<Unit> {
        createBot().use { bot ->
            start(bot, inputs)
            assertEquals(state, storage.get(userId)?.state)

            warn(bot)
        }
    }

    @Test
    fun `each repeated command sends a fresh question without accumulating warnings`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                val original = currentQuestion(bot)
                val ids = mutableSetOf(original.messageId)
                repeat(3) {
                    warn(bot)
                    assertTrue(ids.add(currentQuestion(bot).messageId))
                    assertEquals("$WARNING\n\n${original.text}", currentQuestion(bot).text)
                }
            }
        }

    @Test
    fun `failed recovery send preserves the draft registration and command and allows retry`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME + listOf("Да", "Armbar", "Нет", "Нет", "Да", "Лёгкая тренировка"))
                val original = currentQuestion(bot)
                val session = storage.get(userId)
                val registration = messages.get(userId)
                val token = messages.questionToken(userId)
                val callCount = bot.apiCalls.size
                val eventCount = events.size
                val deletions = bot.deletedMessages
                bot.failNextSendMessage("Bad Request: chat not found")

                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatId.value)

                assertAll(
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registration, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(original, currentQuestion(bot)) },
                    { assertEquals(listOf("sendMessage"), bot.apiCalls.drop(callCount)) },
                    { assertEquals(deletions, bot.deletedMessages) },
                    { assertEquals(emptyList(), replies) },
                    { assertEquals(eventCount, events.size) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                warn(bot)
                assertEquals(session, storage.get(userId))
                assertNotEquals(registration, messages.get(userId))
                assertEquals(original, bot.currentMessage(original.messageId))
            }
        }

    @Test
    fun `next technique name removes warning and advances the same session`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME + "Да")
                val before = assertNotNull(storage.get(userId))
                val details = (before.state as WaitingTechniqueName).details
                warn(bot)
                val registered = assertNotNull(messages.get(userId))
                val callCount = bot.apiCalls.size

                val replies = bot.sendTextAndCollectReplies("Armbar", userId.value, 3_123L)

                assertEquals(
                    before.copy(state = WaitingAddTechniqueDescription(details, "Armbar")),
                    storage.get(userId),
                )
                assertEquals(registered, messages.get(userId))
                val expected =
                    trainingQuestion(
                        chatId,
                        registered.messageId,
                        "📝 Добавить описание техники?",
                        listOf(
                            listOf(
                                EditedTelegramInlineButton("Да", "training:add_technique_description:yes"),
                                EditedTelegramInlineButton("Нет", "training:add_technique_description:no"),
                            ),
                        ),
                    )
                assertEquals(expected, currentQuestion(bot).withoutQuestionToken())
                assertEquals(emptyList(), replies)
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callCount))
                assertEquals(DeletedTelegramMessage(chatId.value, 3_123L), bot.deletedMessages.last())
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `old choices are acknowledged without transitions and new choice applies only once`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                val before = assertNotNull(storage.get(userId))
                val oldQuestion = currentQuestion(bot)
                val stale =
                    listOf("Да", "Нет", "Отменить тренировку").map {
                        bot.captureCallback(oldQuestion, it, userId)
                    }
                warn(bot)
                val registered = assertNotNull(messages.get(userId))
                val eventCount = events.size
                for (callback in stale) {
                    val calls = bot.apiCalls.size
                    val answers = bot.answerCallbackQueryRequests.size
                    val result = bot.deliverCallbackAndCollectReplies(callback)
                    assertTrue(result.acknowledged)
                    assertTrue(result.replies.isEmpty())
                    assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
                    assertEquals(
                        listOf(callback.id),
                        bot.answerCallbackQueryRequests.drop(answers).map { it.callbackQueryId },
                    )
                    assertEquals(before, storage.get(userId))
                    assertEquals(eventCount, events.size)
                    assertEquals(registered, messages.get(userId))
                }
                val yes = bot.captureCallback(currentQuestion(bot), "Да", userId)
                val answers = bot.answerCallbackQueryRequests.size
                val result = bot.deliverCallbackAndCollectReplies(yes)
                assertTrue(result.acknowledged)
                assertTrue(result.replies.isEmpty())
                val details = (before.state as WaitingAddTechnique).details
                assertEquals(before.copy(state = WaitingTechniqueName(details)), storage.get(userId))
                assertEquals(
                    trainingQuestion(chatId, registered.messageId, "🏷️ Введите название техники."),
                    currentQuestion(bot).withoutQuestionToken(),
                )
                val calls = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(yes).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
                assertEquals(
                    listOf(yes.id, yes.id),
                    bot.answerCallbackQueryRequests.drop(answers).map { it.callbackQueryId },
                )
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "Да")), events.drop(eventCount))
                assertEquals(before.copy(state = WaitingTechniqueName(details)), storage.get(userId))
                assertEquals(registered, messages.get(userId))
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `new cancel callback cancels the recovered draft only once`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                warn(bot)
                val registered = assertNotNull(messages.get(userId))
                val callback = bot.captureCallback(currentQuestion(bot), "Отменить тренировку", userId)
                val eventCount = events.size
                val answers = bot.answerCallbackQueryRequests.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertEquals(
                    EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено."),
                    bot.currentMessage(registered.messageId),
                )
                val calls = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), events.drop(eventCount))
                assertEquals(
                    listOf(callback.id, callback.id),
                    bot.answerCallbackQueryRequests.drop(answers).map { it.callbackQueryId },
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private suspend fun warn(bot: TestTelegramBot) {
        val before = assertNotNull(storage.get(userId))
        val original = currentQuestion(bot)
        val token = assertNotNull(messages.questionToken(userId))
        val callCount = bot.apiCalls.size
        val deletionCount = bot.deletedMessages.size
        val eventCount = events.size
        val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatId.value)
        val replacement = assertNotNull(messages.get(userId))
        val expected = warningFor(original.copy(text = original.text.removePrefix("$WARNING\n\n")))
        assertAll(
            { assertEquals(before, storage.get(userId)) },
            { assertEquals(before.sessionId, replacement.sessionId) },
            { assertNotEquals(original.messageId, replacement.messageId) },
            { assertNotEquals(token, messages.questionToken(userId)) },
            { assertEquals(eventCount, events.size) },
            {
                assertEquals(
                    expected.copy(messageId = replacement.messageId).withoutQuestionToken(),
                    currentQuestion(bot).withoutQuestionToken(),
                )
            },
            { assertEquals(original, bot.currentMessage(original.messageId)) },
            { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(callCount)) },
            {
                assertEquals(
                    listOf(DeletedTelegramMessage(chatId.value, repeatId.value)),
                    bot.deletedMessages.drop(deletionCount),
                )
            },
            { assertEquals(replacement.messageId, TelegramMessageId(replies.single().messageId)) },
            { assertTrue(repository.saveCalls.isEmpty()) },
        )
    }

    private fun warningFor(original: EditedTelegramMessage) =
        original.copy(
            text = "$WARNING\n\n${original.text}",
            parseMode = "HTML",
        )

    private suspend fun start(
        bot: TestTelegramBot,
        inputs: List<String>,
    ) {
        bot.sendCommand("/training_add", userId.value, 713L)
        for (input in inputs) {
            if (input == "Да" || input == "Нет") {
                val result = bot.pressInlineButtonAndCollectReplies(currentQuestion(bot), input, userId.value)
                assertEquals(emptyList(), result.replies)
                assertTrue(result.acknowledged)
            } else {
                assertEquals(emptyList(), bot.sendTextAndCollectReplies(input, userId.value))
            }
        }
    }

    private fun currentQuestion(bot: TestTelegramBot) =
        bot.currentMessage(
            messageId = assertNotNull(messages.get(userId)).messageId,
        )

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
                profileRepository = morningpilates.profile.FakeProfileRepository(),
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
