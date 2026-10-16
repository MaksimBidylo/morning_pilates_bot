package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.model.SessionId
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.support.EXPECTED_CANCEL_ROW
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
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

internal class TrainingCreationCallbackGuardTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val userId = TelegramUserId(42L)
    private val otherUserId = TelegramUserId(73L)
    private val chatId = TelegramChatId(4_200L)
    private val events = mutableListOf<FlowEvent>()
    private val repository = FakeTrainingRepository()
    private val service = TrainingFlowService(repository)
    private val manager =
        FlowSessionManager(
            storage,
            listOf(
                object : FlowHandler by service {
                    override fun handle(
                        state: FlowState,
                        event: FlowEvent,
                    ) = service.handle(state, event).also { events += event }
                },
            ),
        )

    @Test
    fun `callback from completed training cannot advance a new training`() =
        runBlocking {
            createBot().use { bot ->
                val firstQuestion = startTraining(bot)
                val firstSession = assertNotNull(storage.get(userId))
                val oldCallback = bot.captureCallback(firstQuestion, "Да", userId)
                press(bot, "Нет")
                press(bot, "Нет")
                press(bot, "Да")
                assertNull(storage.get(userId))
                val secondQuestion = startTraining(bot)
                val secondSession = assertNotNull(storage.get(userId))
                assertNotEquals(firstSession.sessionId, secondSession.sessionId)
                assertNotEquals(firstQuestion.messageId, secondQuestion.messageId)
                assertEquals(firstSession.state, secondSession.state)

                assertIgnored(bot, oldCallback)
            }
        }

    @Test
    fun `duplicate delivery of the same callback has no additional effects`() =
        runBlocking {
            createBot().use { bot ->
                val question = startTraining(bot)
                val callback = bot.captureCallback(question, "Да", userId)
                val first = bot.deliverCallbackAndCollectReplies(callback)
                assertTrue(first.acknowledged)
                assertEquals(emptyList(), first.replies)
                assertEquals(WaitingTechniqueName(TrainingDetails(DATE, TIME)), storage.get(userId)?.state)
                assertEquals(
                    listOf(EXPECTED_CANCEL_ROW),
                    bot.currentMessage(question.messageId).withoutQuestionToken().inlineKeyboard,
                )

                assertIgnored(bot, callback)
            }
        }

    @Test
    fun `delayed callback from first occurrence cannot answer the second identical question`() =
        runBlocking {
            createBot().use { bot ->
                startTraining(bot)
                press(bot, "Да")
                bot.sendTextAndCollectReplies("Armbar", userId.value)
                press(bot, "Нет")
                val firstQuestion = currentQuestion(bot)
                val delayed = bot.captureCallback(firstQuestion, "Да", userId)
                press(bot, "Да")
                bot.sendTextAndCollectReplies("Triangle", userId.value)
                press(bot, "Нет")
                val secondQuestion = currentQuestion(bot)
                val current = bot.captureCallback(secondQuestion, "Да", userId)
                val details = TrainingDetails(DATE, TIME, listOf(TechniqueDraft("Armbar"), TechniqueDraft("Triangle")))
                val session = assertNotNull(storage.get(userId))
                assertEquals(WaitingAddAnotherTechnique(details), session.state)
                assertEquals(firstQuestion.messageId, secondQuestion.messageId)
                assertEquals(firstQuestion.text, secondQuestion.text)
                assertEquals(delayed.from, current.from)
                assertEquals(delayed.message?.chat, current.message?.chat)

                val rejection = runCatching { assertIgnored(bot, delayed) }
                assertAll(
                    { rejection.getOrThrow() },
                    {
                        assertNotEquals(
                            delayed.data,
                            current.data,
                            "Repeated questions need distinct callback payloads",
                        )
                    },
                )
                val interaction = bot.deliverCallbackAndCollectReplies(current)
                assertTrue(interaction.acknowledged)
                assertEquals(emptyList(), interaction.replies)
                assertEquals(session.copy(state = WaitingTechniqueName(details)), storage.get(userId))
                assertEquals("🏷️ Введите название техники.", currentQuestion(bot).text)
                assertEquals(listOf(EXPECTED_CANCEL_ROW), currentQuestion(bot).withoutQuestionToken().inlineKeyboard)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["user", "chat", "message"])
    fun `callback from another owner chat or message is ignored`(source: String) =
        runBlocking {
            createBot().use { bot ->
                val question = startTraining(bot)
                startTraining(bot, otherUserId)
                val callback = bot.captureCallback(question, "Да", userId)
                val message = assertNotNull(callback.message)
                val foreign =
                    when (source) {
                        "user" -> callback.copy(from = callback.from.copy(id = otherUserId.value))
                        "chat" -> callback.copy(message = message.copy(chat = message.chat.copy(id = 5_200L)))
                        "message" -> callback.copy(message = message.copy(messageId = 9_341L))
                        else -> error("Unknown source: $source")
                    }

                assertIgnored(bot, foreign)
            }
        }

    @Test
    fun `registration for a different domain session cannot authorize a callback`() =
        runBlocking {
            createBot().use { bot ->
                val question = startTraining(bot)
                val callback = bot.captureCallback(question, "Да", userId)
                val before = assertNotNull(storage.get(userId))
                check(storage.update(userId, before, before.copy(sessionId = SessionId.new())))

                assertIgnored(bot, callback)
            }
        }

    private suspend fun assertIgnored(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val users = listOf(userId, otherUserId)
        val sessionsBefore = users.map(storage::get)
        val messagesBefore = users.map(messages::get)
        val currentBefore = messagesBefore.filterNotNull().map { bot.currentMessage(it.messageId) }
        val eventCount = events.size
        val editsBefore = bot.editedMessages
        val deletionsBefore = bot.deletedMessages
        val callCount = bot.apiCalls.size

        val interaction = bot.deliverCallbackAndCollectReplies(callback)

        assertAll(
            { assertEquals(sessionsBefore, users.map(storage::get)) },
            { assertEquals(messagesBefore, users.map(messages::get)) },
            { assertEquals(currentBefore, messagesBefore.filterNotNull().map { bot.currentMessage(it.messageId) }) },
            { assertEquals(eventCount, events.size, "Rejected callback must not reach the domain flow") },
            { assertEquals(editsBefore, bot.editedMessages) },
            { assertEquals(deletionsBefore, bot.deletedMessages) },
            { assertEquals(emptyList(), interaction.replies) },
            { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(callCount)) },
            { assertTrue(interaction.acknowledged) },
        )
    }

    private suspend fun startTraining(
        bot: TestTelegramBot,
        user: TelegramUserId = userId,
    ): EditedTelegramMessage {
        bot.sendCommand("/training_add", user.value)
        bot.sendTextAndCollectReplies("12.10.2026", user.value)
        bot.sendTextAndCollectReplies("6:30", user.value)
        assertEquals(WaitingAddTechnique(TrainingDetails(DATE, TIME)), storage.get(user)?.state)
        return bot.currentMessage(assertNotNull(messages.get(user)).messageId)
    }

    private fun currentQuestion(bot: TestTelegramBot) =
        bot.currentMessage(
            messageId = assertNotNull(messages.get(userId)).messageId,
        )

    private suspend fun press(
        bot: TestTelegramBot,
        answer: String,
    ) {
        val interaction = bot.pressInlineButtonAndCollectReplies(currentQuestion(bot), answer, userId.value)
        assertTrue(interaction.acknowledged)
    }

    private fun createBot() =
        TestTelegramBot(
            telegramBot =
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

    companion object {
        private val DATE = LocalDate.of(2026, 10, 12)
        private val TIME = LocalTime.of(6, 30)
    }
}
