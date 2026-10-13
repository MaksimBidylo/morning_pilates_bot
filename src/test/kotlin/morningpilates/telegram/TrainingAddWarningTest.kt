package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WARNING =
    "⚠️ <b>У вас есть незавершённая тренировка</b>\n\n" +
        "Продолжите или отмените её, прежде чем создавать новую."
private val DATE_TIME = listOf("12.10.2026", "6:30")

internal class TrainingAddWarningTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val repeatId = TelegramMessageId(2_713L)
    private val events = mutableListOf<FlowEvent>()

    @ParameterizedTest(name = "repeated command in {0}")
    @MethodSource("morningpilates.telegram.TrainingCancelCommandTest#waitingStates")
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
    fun `repeated identical warnings including message not modified delete every command`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                val original = currentQuestion(bot)
                val session = storage.get(userId)
                val registration = messages.get(userId)
                val token = messages.questionToken(userId)
                val eventCount = events.size
                val callCount = bot.apiCalls.size
                val editCount = bot.editedMessages.size
                val deletionCount = bot.deletedMessages.size
                bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatId.value)
                bot.failNextEditMessageText("Bad Request: message is not modified")
                bot.sendCommandAndCollectReplies("/training_add", userId.value, 2_829L)
                bot.sendCommandAndCollectReplies("/training_add", userId.value, 2_947L)
                val expected = warningFor(original)

                assertAll(
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registration, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(eventCount, events.size) },
                    { assertEquals(listOf(expected, expected, expected), bot.editedMessages.drop(editCount)) },
                    { assertEquals(expected, currentQuestion(bot)) },
                    {
                        assertEquals(
                            listOf(
                                "editMessageText",
                                "deleteMessage",
                                "editMessageText",
                                "deleteMessage",
                                "editMessageText",
                                "deleteMessage",
                            ),
                            bot.apiCalls.drop(callCount),
                        )
                    },
                    {
                        assertEquals(
                            listOf(repeatId.value, 2_829L, 2_947L).map { DeletedTelegramMessage(chatId.value, it) },
                            bot.deletedMessages.drop(deletionCount),
                        )
                    },
                    { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @Test
    fun `another edit error keeps the command and session intact`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                val original = currentQuestion(bot)
                val session = storage.get(userId)
                val registration = messages.get(userId)
                val token = messages.questionToken(userId)
                val callCount = bot.apiCalls.size
                val editCount = bot.editedMessages.size
                val deletions = bot.deletedMessages
                bot.failNextEditMessageText("Bad Request: can't parse entities")

                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatId.value)

                assertAll(
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registration, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(original, currentQuestion(bot)) },
                    { assertEquals(listOf(warningFor(original)), bot.editedMessages.drop(editCount)) },
                    { assertEquals(listOf("editMessageText"), bot.apiCalls.drop(callCount)) },
                    { assertEquals(deletions, bot.deletedMessages) },
                    { assertEquals(emptyList(), replies) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @Test
    fun `next technique name removes warning and advances the same session`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME + "Да")
                val before = assertNotNull(storage.get(userId))
                val details = (before.state as WaitingTechniqueName).details
                val registered = assertNotNull(messages.get(userId))
                warn(bot)
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
                        "Добавить описание техники?",
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
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `existing yes callback works after warning and removes it`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                val before = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val oldCallback = bot.captureCallback(currentQuestion(bot), "Да", userId)
                warn(bot)
                val callCount = bot.apiCalls.size
                val eventCount = events.size

                val result = bot.deliverCallbackAndCollectReplies(oldCallback)

                val details = (before.state as WaitingAddTechnique).details
                assertEquals(before.copy(state = WaitingTechniqueName(details)), storage.get(userId))
                assertEquals(registered, messages.get(userId))
                assertEquals(
                    trainingQuestion(chatId, registered.messageId, "Введите название техники."),
                    currentQuestion(bot).withoutQuestionToken(),
                )
                assertEquals(listOf<FlowEvent>(FlowEvent.UserInput(userId, "Да")), events.drop(eventCount))
                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callCount))
                assertEquals(emptyList(), result.replies)
                assertTrue(result.acknowledged)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @Test
    fun `existing cancel callback stays valid after warning`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, DATE_TIME)
                val registered = assertNotNull(messages.get(userId))
                val callback = bot.captureCallback(currentQuestion(bot), "Отменить тренировку", userId)
                warn(bot)
                val callCount = bot.apiCalls.size
                val eventCount = events.size

                val result = bot.deliverCallbackAndCollectReplies(callback)

                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
                assertEquals(
                    EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено."),
                    bot.currentMessage(registered.messageId),
                )
                assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), events.drop(eventCount))
                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callCount))
                assertEquals(emptyList(), result.replies)
                assertTrue(result.acknowledged)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private suspend fun warn(bot: TestTelegramBot) {
        val before = assertNotNull(storage.get(userId))
        val registered = assertNotNull(messages.get(userId))
        val original = currentQuestion(bot)
        val token = assertNotNull(messages.questionToken(userId))
        val callCount = bot.apiCalls.size
        val editCount = bot.editedMessages.size
        val deletionCount = bot.deletedMessages.size
        val eventCount = events.size
        val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatId.value)
        val expected = warningFor(original)
        assertAll(
            { assertEquals(before, storage.get(userId)) },
            { assertEquals(registered, messages.get(userId)) },
            { assertEquals(token, messages.questionToken(userId)) },
            { assertEquals(eventCount, events.size) },
            { assertEquals(expected, currentQuestion(bot)) },
            { assertEquals(listOf(expected), bot.editedMessages.drop(editCount)) },
            { assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callCount)) },
            {
                assertEquals(
                    listOf(DeletedTelegramMessage(chatId.value, repeatId.value)),
                    bot.deletedMessages.drop(deletionCount),
                )
            },
            { assertEquals(emptyList(), replies) },
            { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
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
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )
    }
}
