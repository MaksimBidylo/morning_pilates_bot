package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.command.CancelCommandTest
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CANCEL_TEXT = "Отменить тренировку"
private const val CANCEL_DATA = "training:cancel"
private val CANCEL_ROW = listOf(EditedTelegramInlineButton(CANCEL_TEXT, CANCEL_DATA))

internal class TrainingCreationInlineCancelTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val userId = TelegramUserId(42L)
    private val otherUserId = TelegramUserId(73L)
    private val chatId = TelegramChatId(4_200L)
    private val events = mutableListOf<FlowEvent>()
    private val results = mutableListOf<FlowResult>()

    @Test
    fun `declining final confirmation cancels in the original message and permits a new training`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, listOf("12.10.2026", "6:30", "Да", "Armbar", "Нет", "Нет", "Нет"))
                val before = assertNotNull(storage.get(userId))
                assertTrue(before.state is WaitingConfirmation)
                val registered = assertNotNull(messages.get(userId))
                val callback = bot.captureCallback(currentQuestion(bot), "Нет", userId)
                val calls = bot.apiCalls.size
                val editCount = bot.editedMessages.size
                val eventCount = events.size
                val resultCount = results.size
                val deletions = bot.deletedMessages

                val interaction = bot.deliverCallbackAndCollectReplies(callback)

                val expected = EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено.")
                assertAll(
                    { assertEquals(listOf<FlowResult>(FlowResult.Cancelled), results.drop(resultCount)) },
                    {
                        assertEquals(
                            listOf<FlowEvent>(FlowEvent.UserInput(userId, "Нет")),
                            events.drop(eventCount),
                        )
                    },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                    { assertEquals(expected, bot.currentMessage(registered.messageId)) },
                    { assertEquals(listOf(expected), bot.editedMessages.drop(editCount)) },
                    {
                        assertEquals(
                            listOf("editMessageText", "answerCallbackQuery"),
                            bot.apiCalls.drop(calls),
                        )
                    },
                    { assertTrue(interaction.acknowledged) },
                    { assertTrue(interaction.replies.isEmpty()) },
                    { assertEquals(deletions, bot.deletedMessages) },
                    { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
                )
                assertIgnored(bot, callback)
                start(bot, listOf("13.10.2026", "7:45"))
                val nextSession = assertNotNull(storage.get(userId))
                val nextMessage = assertNotNull(messages.get(userId))
                assertNotEquals(before.sessionId, nextSession.sessionId)
                assertNotEquals(registered.messageId, nextMessage.messageId)
                assertIgnored(bot, callback)
            }
        }

    @ParameterizedTest(name = "inline cancel in {0}")
    @MethodSource("morningpilates.telegram.command.CancelCommandTest#waitingStates")
    fun `cancel button is available and clears every waiting state`(
        state: TrainingFlowState,
        inputs: List<String>,
    ) = runBlocking<Unit> {
        createBot().use { bot ->
            start(bot, inputs)
            assertEquals(state, storage.get(userId)?.state)
            assertKeyboard(currentQuestion(bot), state)

            cancel(bot)
        }
    }

    @Test
    fun `inline cancel leaves one plain message and old button cannot cancel the next training`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, listOf("12.10.2026", "6:30", "Да", "Armbar"))
                val oldSession = assertNotNull(storage.get(userId))
                val oldMessage = assertNotNull(messages.get(userId))
                val callback = cancel(bot)
                assertIgnored(bot, callback)
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })

                start(bot, listOf("13.10.2026", "7:45", "Да", "Triangle"))
                val newSession = assertNotNull(storage.get(userId))
                val newMessage = assertNotNull(messages.get(userId))
                assertNotEquals(oldSession.sessionId, newSession.sessionId)
                assertNotEquals(oldMessage.messageId, newMessage.messageId)
                assertEquals("Triangle", (newSession.state as WaitingAddTechniqueDescription).techniqueName)

                assertIgnored(bot, callback)
                cancel(bot)
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    @Test
    fun `cancel remains usable after repeated invalid time and message not modified`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, listOf("12.10.2026"))
                val before = assertNotNull(storage.get(userId))
                assertEquals(emptyList(), bot.sendTextAndCollectReplies("25:61", userId.value, 829L))
                val errorQuestion = currentQuestion(bot)
                assertTrue(errorQuestion.text.startsWith("⚠️ "))
                assertTrue(errorQuestion.text.contains("🕒 Введите время начала тренировки."))
                assertTrue(errorQuestion.text.contains("Например: 8:00, 19:30 или 8-00."))
                assertKeyboard(errorQuestion, before.state as TrainingFlowState)
                val oldButton = cancelCallback(bot)
                bot.failNextEditMessageText("Bad Request: message is not modified")
                val callCount = bot.apiCalls.size
                val editCount = bot.editedMessages.size

                assertEquals(emptyList(), bot.sendTextAndCollectReplies("26:61", userId.value, 947L))

                assertEquals(before, storage.get(userId))
                assertEquals(listOf(errorQuestion), bot.editedMessages.drop(editCount))
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callCount))
                cancel(bot, oldButton)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["user", "chat", "message"])
    fun `foreign cancel callback cannot change either active session`(source: String) =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, listOf("12.10.2026", "6:30"))
                start(bot, listOf("13.10.2026", "7:45"), otherUserId)
                val callback = cancelCallback(bot)
                val message = assertNotNull(callback.message)
                val foreign =
                    when (source) {
                        "user" -> callback.copy(from = callback.from.copy(id = otherUserId.value))
                        "chat" -> callback.copy(message = message.copy(chat = message.chat.copy(id = 5_200L)))
                        "message" -> callback.copy(message = message.copy(messageId = 9_341L))
                        else -> error("Unknown source: $source")
                    }

                assertIgnored(bot, foreign)
                cancel(bot, callback)
                assertNotNull(storage.get(otherUserId))
                assertNotNull(messages.get(otherUserId))
            }
        }

    @Test
    fun `old cancel from repeated question is ignored while current yes and cancel work`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                start(bot, listOf("12.10.2026", "6:30", "Да", "Armbar", "Нет"))
                assertTrue(storage.get(userId)?.state is WaitingAddAnotherTechnique)
                val firstQuestion = currentQuestion(bot)
                val oldCancel = cancelCallback(bot)
                enter(bot, listOf("Да", "Triangle", "Нет"))
                assertTrue(storage.get(userId)?.state is WaitingAddAnotherTechnique)
                val secondQuestion = currentQuestion(bot)
                val newCancel = cancelCallback(bot)
                assertEquals(firstQuestion.messageId, secondQuestion.messageId)
                assertEquals(firstQuestion.text, secondQuestion.text)
                assertNotEquals(oldCancel.data, newCancel.data)

                assertIgnored(bot, oldCancel)
                enter(bot, listOf("Да"))
                assertTrue(storage.get(userId)?.state is WaitingTechniqueName)
                cancel(bot)
            }
        }

    private suspend fun cancel(
        bot: TestTelegramBot,
        callback: CallbackQuery = cancelCallback(bot),
    ): CallbackQuery {
        val registered = assertNotNull(messages.get(userId))
        val eventCount = events.size
        val callCount = bot.apiCalls.size
        val editCount = bot.editedMessages.size
        val deletions = bot.deletedMessages
        val sendCount = bot.apiCalls.count { it == "sendMessage" }
        val result = bot.deliverCallbackAndCollectReplies(callback)
        val expected = EditedTelegramMessage(chatId, registered.messageId, "Создание тренировки отменено.")
        assertAll(
            { assertEquals(listOf<FlowEvent>(FlowEvent.Cancel(userId)), events.drop(eventCount)) },
            { assertNull(storage.get(userId)) },
            { assertNull(messages.get(userId)) },
            { assertEquals(expected, bot.currentMessage(registered.messageId)) },
            { assertEquals(listOf(expected), bot.editedMessages.drop(editCount)) },
            { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callCount)) },
            { assertEquals(emptyList(), result.replies) },
            { assertTrue(result.acknowledged) },
            { assertEquals(deletions, bot.deletedMessages) },
            { assertEquals(sendCount, bot.apiCalls.count { it == "sendMessage" }) },
            { assertTrue(repository.saveCalls.isEmpty()) },
            { assertFalse(messages.consumeQuestion(userId, registered, callback.data)) },
        )
        return callback
    }

    private suspend fun assertIgnored(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val users = listOf(userId, otherUserId)
        val sessions = users.map(storage::get)
        val registrations = users.map(messages::get)
        val current = registrations.filterNotNull().map { bot.currentMessage(it.messageId) }
        val eventCount = events.size
        val callCount = bot.apiCalls.size
        val edits = bot.editedMessages
        val deletions = bot.deletedMessages
        val result = bot.deliverCallbackAndCollectReplies(callback)
        assertAll(
            { assertEquals(sessions, users.map(storage::get)) },
            { assertEquals(registrations, users.map(messages::get)) },
            { assertEquals(current, registrations.filterNotNull().map { bot.currentMessage(it.messageId) }) },
            { assertEquals(eventCount, events.size) },
            { assertEquals(edits, bot.editedMessages) },
            { assertEquals(deletions, bot.deletedMessages) },
            { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(callCount)) },
            { assertEquals(emptyList(), result.replies) },
            { assertTrue(result.acknowledged) },
            { assertTrue(repository.saveCalls.isEmpty()) },
        )
    }

    private fun cancelCallback(bot: TestTelegramBot): CallbackQuery {
        val question = currentQuestion(bot)
        assertTrue(question.inlineKeyboard.flatten().any { it.text == CANCEL_TEXT }, "Cancel button must be present")
        assertEquals(CANCEL_ROW, question.withoutQuestionToken().inlineKeyboard.last())
        return bot.captureCallback(question, CANCEL_TEXT, userId)
    }

    private fun assertKeyboard(
        question: EditedTelegramMessage,
        state: TrainingFlowState,
    ) {
        val action =
            when (state) {
                is WaitingAddTechnique -> "add_technique"
                is WaitingAddTechniqueDescription -> "add_technique_description"
                is WaitingAddAnotherTechnique -> "add_another_technique"
                is WaitingAddNote -> "add_note"
                is WaitingConfirmation -> "confirmation"
                else -> null
            }
        val rows =
            if (action == null) {
                emptyList()
            } else {
                listOf(
                    listOf(
                        EditedTelegramInlineButton("Да", "training:$action:yes"),
                        EditedTelegramInlineButton("Нет", "training:$action:no"),
                    ),
                )
            }
        assertEquals(rows + listOf(CANCEL_ROW), question.withoutQuestionToken().inlineKeyboard)
    }

    private suspend fun start(
        bot: TestTelegramBot,
        inputs: List<String>,
        user: TelegramUserId = userId,
    ) {
        bot.sendCommand("/training_add", user.value)
        enter(bot, inputs, user)
    }

    private suspend fun enter(
        bot: TestTelegramBot,
        inputs: List<String>,
        user: TelegramUserId = userId,
    ) {
        for (input in inputs) {
            if (input == "Да" || input == "Нет") {
                val question = bot.currentMessage(assertNotNull(messages.get(user)).messageId)
                val result = bot.pressInlineButtonAndCollectReplies(question, input, user.value)
                assertEquals(emptyList(), result.replies)
                assertTrue(result.acknowledged)
            } else {
                assertEquals(emptyList(), bot.sendTextAndCollectReplies(input, user.value))
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
                ) = service.handle(state, event).also {
                    events += event
                    results += it
                }
            }
        val manager = FlowSessionManager(storage, listOf(observed))
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
}
