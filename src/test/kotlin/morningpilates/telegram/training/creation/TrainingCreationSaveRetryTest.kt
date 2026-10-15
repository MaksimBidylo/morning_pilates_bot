package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.trainingQuestion
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.FakeTrainingRepository.SaveCall
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingQueryService
import morningpilates.training.toTrainingId
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAVE_ERROR = "Не удалось сохранить тренировку. Попробуйте ещё раз."
private val RETRY_DETAILS =
    TrainingDetails(
        LocalDate.of(2026, 10, 13),
        LocalTime.of(6, 30),
        listOf(TechniqueDraft("Armbar", "Рычаг локтя из маунта")),
        "Отрабатывали выход из сайд-контроля",
    )
private val SAVED_RETRY_TEXT =
    """
    Тренировка сохранена.

    <blockquote expandable>Дата: 13.10.2026
    Время: 06:30

    Техники:
    1. Armbar
       Описание: Рычаг локтя из маунта

    Заметка: Отрабатывали выход из сайд-контроля</blockquote>
    """.trimIndent()

internal class TrainingCreationSaveRetryTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @Test
    fun `repository failure edits the same confirmation and preserves the complete session`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                prepareConfirmation(bot)
                failSaving(bot)
            }
        }

    @Test
    fun `fresh confirmation retries the same training while the previous token is ignored`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                prepareConfirmation(bot)
                val before = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val oldCallback = bot.captureCallback(currentQuestion(bot), "Да", userId)
                failSaving(bot)
                val retryQuestion = currentQuestion(bot)
                val callCount = bot.apiCalls.size
                val edits = bot.editedMessages
                val deletions = bot.deletedMessages
                val token = messages.questionToken(userId)
                val stale = bot.deliverCallbackAndCollectReplies(oldCallback)
                assertAll(
                    { assertTrue(stale.acknowledged) },
                    { assertTrue(stale.replies.isEmpty()) },
                    { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(callCount)) },
                    { assertEquals(1, repository.saveCalls.size) },
                    { assertEquals(before, storage.get(userId)) },
                    { assertEquals(registered, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(retryQuestion, currentQuestion(bot)) },
                    { assertEquals(edits, bot.editedMessages) },
                    { assertEquals(deletions, bot.deletedMessages) },
                )
                repository.saveResult = Result.success(Unit)
                val retryCallCount = bot.apiCalls.size
                val retryEditCount = bot.editedMessages.size

                val retried = bot.pressInlineButtonAndCollectReplies(retryQuestion, "Да", userId.value)

                val expectedSave = SaveCall(userId, before.sessionId.toTrainingId(), RETRY_DETAILS)
                val expectedCard =
                    EditedTelegramMessage(chatId, registered.messageId, SAVED_RETRY_TEXT, parseMode = "HTML")
                assertAll(
                    { assertEquals(listOf(expectedSave, expectedSave), repository.saveCalls) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(retried.acknowledged) },
                    { assertTrue(retried.replies.isEmpty()) },
                    {
                        assertEquals(
                            listOf("editMessageText", "answerCallbackQuery"),
                            bot.apiCalls.drop(retryCallCount),
                        )
                    },
                    { assertEquals(listOf(expectedCard), bot.editedMessages.drop(retryEditCount)) },
                    { assertEquals(expectedCard, bot.currentMessage(registered.messageId)) },
                    { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
                    { assertEquals(deletions, bot.deletedMessages) },
                )
            }
        }

    @Test
    fun `Telegram edit failure cannot undo a saved training or prevent starting the next one`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                prepareConfirmation(bot)
                val before = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                val originalQuestion = currentQuestion(bot)
                val callback = bot.captureCallback(originalQuestion, "Да", userId)
                val callCount = bot.apiCalls.size
                val editCount = bot.editedMessages.size
                bot.failNextEditMessageText("Bad Request: message to edit not found")

                val saved = bot.deliverCallbackAndCollectReplies(callback)

                val expectedSave = SaveCall(userId, before.sessionId.toTrainingId(), RETRY_DETAILS)
                assertEquals(listOf(expectedSave), repository.saveCalls)
                assertNull(storage.get(userId))
                assertTrue(saved.acknowledged)
                assertTrue(saved.replies.isEmpty())
                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callCount))
                assertEquals(
                    listOf(EditedTelegramMessage(chatId, registered.messageId, SAVED_RETRY_TEXT, parseMode = "HTML")),
                    bot.editedMessages.drop(editCount),
                )
                assertEquals(originalQuestion, bot.currentMessage(registered.messageId))
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertIgnored(bot, callback)

                bot.sendCommand("/training_add", userId.value, 2_713L)
                val nextSession = assertNotNull(storage.get(userId))
                val nextMessage = assertNotNull(messages.get(userId))
                assertNotEquals(before.sessionId, nextSession.sessionId)
                assertNotEquals(registered.messageId, nextMessage.messageId)
                assertIgnored(bot, callback)
                assertEquals(listOf(expectedSave), repository.saveCalls)
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
            }
        }

    private suspend fun failSaving(bot: TestTelegramBot) {
        val before = assertNotNull(storage.get(userId))
        val registered = assertNotNull(messages.get(userId))
        val previousToken = assertNotNull(messages.questionToken(userId))
        val callCount = bot.apiCalls.size
        val editCount = bot.editedMessages.size
        val deletions = bot.deletedMessages
        repository.saveResult = Result.failure(IllegalStateException("PostgreSQL unavailable"))

        val result = bot.pressInlineButtonAndCollectReplies(currentQuestion(bot), "Да", userId.value)

        val expected =
            trainingQuestion(
                chatId,
                registered.messageId,
                SAVE_ERROR,
                listOf(
                    listOf(
                        EditedTelegramInlineButton("Да", "training:confirmation:yes"),
                        EditedTelegramInlineButton("Нет", "training:confirmation:no"),
                    ),
                ),
            )
        val expectedSave = SaveCall(userId, before.sessionId.toTrainingId(), RETRY_DETAILS)
        assertAll(
            { assertEquals(before, storage.get(userId)) },
            { assertEquals(registered, messages.get(userId)) },
            { assertEquals(listOf(expectedSave), repository.saveCalls) },
            { assertTrue(result.acknowledged) },
            { assertTrue(result.replies.isEmpty()) },
            { assertEquals(listOf(expected), bot.editedMessages.drop(editCount).map { it.withoutQuestionToken() }) },
            { assertEquals(expected, currentQuestion(bot).withoutQuestionToken()) },
            { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callCount)) },
            { assertNotEquals(previousToken, assertNotNull(messages.questionToken(userId))) },
            { assertEquals(deletions, bot.deletedMessages) },
            { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
        )
    }

    private suspend fun assertIgnored(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val session = storage.get(userId)
        val registration = messages.get(userId)
        val token = messages.questionToken(userId)
        val saves = repository.saveCalls.toList()
        val edits = bot.editedMessages
        val deletions = bot.deletedMessages
        val callCount = bot.apiCalls.size
        val result = bot.deliverCallbackAndCollectReplies(callback)
        assertTrue(result.acknowledged)
        assertTrue(result.replies.isEmpty())
        assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(callCount))
        assertEquals(session, storage.get(userId))
        assertEquals(registration, messages.get(userId))
        assertEquals(token, messages.questionToken(userId))
        assertEquals(saves, repository.saveCalls)
        assertEquals(edits, bot.editedMessages)
        assertEquals(deletions, bot.deletedMessages)
    }

    private suspend fun prepareConfirmation(bot: TestTelegramBot) {
        bot.sendCommand("/training_add", userId.value, 713L)
        val initial = assertNotNull(storage.get(userId))
        val registered = assertNotNull(messages.get(userId))
        for (input in listOf(
            "13.10.2026",
            "6:30",
            "Да",
            "Armbar",
            "Да",
            "Рычаг локтя из маунта",
            "Нет",
            "Да",
            "Отрабатывали выход из сайд-контроля",
        )) {
            if (input == "Да" || input == "Нет") {
                val result = bot.pressInlineButtonAndCollectReplies(currentQuestion(bot), input, userId.value)
                assertTrue(result.acknowledged)
                assertTrue(result.replies.isEmpty())
            } else {
                assertTrue(bot.sendTextAndCollectReplies(input, userId.value).isEmpty())
            }
            assertEquals(initial.sessionId, storage.get(userId)?.sessionId)
            assertEquals(registered, messages.get(userId))
        }
        assertEquals(initial.copy(state = WaitingConfirmation(RETRY_DETAILS)), storage.get(userId))
        assertTrue(repository.saveCalls.isEmpty())
    }

    private fun currentQuestion(bot: TestTelegramBot): EditedTelegramMessage {
        val registered = assertNotNull(messages.get(userId))
        return bot.currentMessage(registered.messageId)
    }

    private fun createBot() =
        TestTelegramBot(
            TelegramBot(
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
