package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
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
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

internal class TrainingCancelCommandTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val cancelMessageId = TelegramMessageId(2_713L)
    private val domainEvents = mutableListOf<FlowEvent>()
    private val domainResults = mutableListOf<FlowResult>()

    @Test
    fun `cancel without a session replies before deleting the command`() =
        runBlocking {
            createBot().use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/cancel", userId.value, cancelMessageId.value)

                assertEquals(listOf(SentTelegramMessage(chatId.value, "Нет активной тренировки.")), replies)
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
        registered: TrainingMessageSession,
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

    private fun createBot(): TestTelegramBot {
        val service = TrainingFlowService(repository)
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
            telegramBot = TelegramBot(TelegramProperties("test-token"), storage, manager, messages),
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
