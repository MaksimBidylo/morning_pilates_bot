package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DATE_VIEW_CHANGE
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.DATE_VIEW_PREVIOUS
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.NEXT_SEARCH_DATE
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TelegramCallbackAnswer
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingFlowState.WaitingDate
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOAD_ERROR = "Не удалось загрузить тренировки. Попробуйте ещё раз."
private const val RETRY = "Повторить попытку"
private const val TECHNICAL_ERROR = "date-query-connection-failed SQLSTATE 08006 internal-host:5432"

internal class TrainingDateViewRetryTest {
    private val fixture = TrainingDateViewTestFixture()

    @Test
    fun `initial date query failure displays an error and deletes input only after Telegram responds`() =
        runBlocking<Unit> {
            configureFailure()
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                val input = TelegramMessageId(9_000L)
                var displayResponded = false
                bot.beforeNextEditMessageTextResponse {
                    assertTrue(bot.deleteMessageAttempts.none { it.messageId == input })
                    displayResponded = true
                }
                val before = bot.apiCalls.size

                val error = fixture.enterDate(bot, question, messageId = input.value)

                assertAll(
                    { assertLoadError(error) },
                    { assertEquals(question.messageId, error.messageId) },
                    { assertEquals(fixture.chat, error.chatId) },
                    { assertDateReads(SEARCH_DATE) },
                    { assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(before)) },
                    { assertTrue(displayResponded, "The error screen must be displayed before deleting input") },
                    { assertTrue(TelegramDeleteAttempt(fixture.chat, input, true) in bot.deleteMessageAttempts) },
                    { assertEquals(1, bot.sendMessageRequests.size) },
                    { fixture.assertNoCreation() },
                )
            }
        }

    @ParameterizedTest(name = "retry returns {0} trainings")
    @ValueSource(ints = [0, 1, 3])
    fun `retry reloads the selected date and retires the error keyboard after success`(count: Int) =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val error = openError(bot)
                val oldRetry = bot.captureCallback(error, RETRY, fixture.user)
                val values = fixture.trainings.take(count)
                fixture.configure(values)

                val result = press(bot, error, RETRY)

                assertEquals(error.messageId, result.messageId)
                assertResults(result, values)
                assertDateReads(SEARCH_DATE, SEARCH_DATE)
                if (count > 1) {
                    val next = press(bot, result, DATE_VIEW_NEXT)
                    fixture.assertCard(next, values, 1)
                    val previous = press(bot, next, DATE_VIEW_PREVIOUS)
                    fixture.assertCard(previous, values, 0)
                    assertDateReads(SEARCH_DATE, SEARCH_DATE)
                }
                assertStaleRetry(bot, oldRetry)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `another failure preserves the selected date and permits a fresh manual retry`() =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val first = openError(bot)

                val second = press(bot, first, RETRY)

                assertLoadError(second)
                assertEquals(first.messageId, second.messageId)
                assertDateReads(SEARCH_DATE, SEARCH_DATE)
                fixture.configure(fixture.trainings)

                val recovered = press(bot, second, RETRY)

                fixture.assertCard(recovered, fixture.trainings, 0)
                assertEquals(first.messageId, recovered.messageId)
                assertDateReads(SEARCH_DATE, SEARCH_DATE, SEARCH_DATE)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `a new search replaces the failed date used by retry`() =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val first = openError(bot)
                val oldRetry = bot.captureCallback(first, RETRY, fixture.user)

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date", fixture.user.value).isEmpty())

                val question = bot.currentMessage(first.messageId)
                fixture.assertQuestion(question)
                assertDateReads(SEARCH_DATE)
                configureFailure(NEXT_SEARCH_DATE)
                val second = fixture.enterDate(bot, question, "13.10.2026", messageId = 9_001L)
                assertLoadError(second)
                assertDateReads(SEARCH_DATE, NEXT_SEARCH_DATE)
                assertStaleRetry(bot, oldRetry)
                fixture.configure(emptyList(), NEXT_SEARCH_DATE)

                val recovered = press(bot, second, RETRY)

                assertEquals(first.messageId, recovered.messageId)
                assertEquals("За 13.10.2026 тренировок не найдено.", recovered.text)
                fixture.assertButtons(recovered, setOf(DATE_VIEW_CHANGE, DATE_VIEW_FINISH))
                assertDateReads(SEARCH_DATE, NEXT_SEARCH_DATE, NEXT_SEARCH_DATE)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest(name = "latest query fails = {0}")
    @ValueSource(booleans = [false, true])
    fun `switching to latest retires date retry even when latest also shows an error`(latestFails: Boolean) =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val dateError = openError(bot)
                val oldRetry = bot.captureCallback(dateError, RETRY, fixture.user)
                fixture.repository.findLatestResults[fixture.user] =
                    if (latestFails) {
                        Result.failure(IllegalStateException(TECHNICAL_ERROR))
                    } else {
                        Result.success(fixture.trainings.first())
                    }

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", fixture.user.value).isEmpty())

                val latest = bot.currentMessage(dateError.messageId)
                if (latestFails) assertLoadError(latest) else assertTrue(latest.text.startsWith("Последняя тренировка"))
                assertEquals(listOf(fixture.user), fixture.repository.findLatestCalls)
                assertStaleRetry(bot, oldRetry)
                if (latestFails) {
                    fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.first())
                    val recovered = press(bot, latest, RETRY)
                    assertTrue(recovered.text.startsWith("Последняя тренировка"))
                    fixture.assertButtons(recovered, setOf(DATE_VIEW_FINISH))
                    assertEquals(listOf(fixture.user, fixture.user), fixture.repository.findLatestCalls)
                }
                assertEquals(listOf(FindByDateCall(fixture.user, SEARCH_DATE)), fixture.repository.findByDateCalls)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest
    @EnumSource(CloseAction::class)
    fun `closing an error view retires retry and preserves the next action`(action: CloseAction) =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val error = openError(bot)
                val oldRetry = bot.captureCallback(error, RETRY, fixture.user)

                val replies = close(bot, error, action)

                assertTrue(TelegramDeleteAttempt(fixture.chat, error.messageId, true) in bot.deleteMessageAttempts)
                assertCloseReplies(action, replies)
                val created = fixture.messages.get(fixture.user)?.let { bot.currentMessage(it.messageId) }
                assertStaleRetry(bot, oldRetry)
                if (created != null) {
                    assertEquals(created, bot.currentMessage(created.messageId))
                    press(bot, created, "Отменить тренировку")
                }
                val events = fixture.events.toList()
                val fresh = fixture.openQuestion(bot)
                assertNotEquals(error.messageId, fresh.messageId)
                assertStaleRetry(bot, oldRetry)
                assertEquals(fresh, bot.currentMessage(fresh.messageId))
                assertDateReads(SEARCH_DATE)
                assertEquals(events, fixture.events)
                assertNull(fixture.storage.get(fixture.user))
                assertNull(fixture.messages.get(fixture.user))
                assertTrue(fixture.repository.saveCalls.isEmpty())
            }
        }

    private fun configureFailure(date: LocalDate = SEARCH_DATE) {
        fixture.repository.findByDateResults[FindByDateCall(fixture.user, date)] =
            Result.failure(IllegalStateException(TECHNICAL_ERROR))
    }

    private suspend fun openError(bot: TestTelegramBot): EditedTelegramMessage {
        configureFailure()
        val question = fixture.openQuestion(bot)
        val error = fixture.enterDate(bot, question)
        assertLoadError(error)
        return error
    }

    private fun assertLoadError(message: EditedTelegramMessage) {
        assertEquals(LOAD_ERROR, message.text)
        assertFalse(message.text.contains(TECHNICAL_ERROR))
        fixture.assertButtons(message, setOf(RETRY, DATE_VIEW_FINISH))
    }

    private fun assertResults(
        message: EditedTelegramMessage,
        values: List<SavedTraining>,
    ) {
        if (values.isEmpty()) {
            assertEquals("За 12.10.2026 тренировок не найдено.", message.text)
            fixture.assertButtons(message, setOf(DATE_VIEW_CHANGE, DATE_VIEW_FINISH))
        } else {
            fixture.assertCard(message, values, 0)
        }
    }

    private fun assertDateReads(vararg dates: LocalDate) {
        assertEquals(dates.map { FindByDateCall(fixture.user, it) }, fixture.repository.findByDateCalls)
        assertTrue(fixture.repository.findLatestCalls.isEmpty())
    }

    private suspend fun press(
        bot: TestTelegramBot,
        message: EditedTelegramMessage,
        button: String,
    ): EditedTelegramMessage {
        val before = bot.answerCallbackQueryRequests.size
        val current = fixture.press(bot, message, button)
        assertEquals(
            before + 1,
            bot.answerCallbackQueryRequests.size,
            "Each callback must be acknowledged exactly once",
        )
        assertEquals(message.messageId, current.messageId)
        return current
    }

    private suspend fun assertStaleRetry(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val before = bot.apiCalls.size
        val answers = bot.answerCallbackQueryRequests.size
        val dateReads = fixture.repository.findByDateCalls.toList()
        val latestReads = fixture.repository.findLatestCalls.toList()
        val message = bot.currentMessage(TelegramMessageId(assertNotNull(callback.message).messageId))
        val session = fixture.storage.get(fixture.user)
        val registration = fixture.messages.get(fixture.user)
        val events = fixture.events.toList()

        val delivered = bot.deliverCallbackAndCollectReplies(callback)

        assertTrue(delivered.acknowledged)
        assertTrue(delivered.replies.isEmpty())
        assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
        assertEquals(listOf(TelegramCallbackAnswer(callback.id, null)), bot.answerCallbackQueryRequests.drop(answers))
        assertEquals(dateReads, fixture.repository.findByDateCalls)
        assertEquals(latestReads, fixture.repository.findLatestCalls)
        assertEquals(message, bot.currentMessage(message.messageId))
        assertEquals(session, fixture.storage.get(fixture.user))
        assertEquals(registration, fixture.messages.get(fixture.user))
        assertEquals(events, fixture.events)
    }

    private suspend fun close(
        bot: TestTelegramBot,
        error: EditedTelegramMessage,
        action: CloseAction,
    ): List<SentTelegramMessage> =
        if (action == CloseAction.FINISH) {
            press(bot, error, DATE_VIEW_FINISH)
            emptyList()
        } else {
            bot.sendCommandAndCollectReplies(action.command, fixture.user.value)
        }

    private fun assertCloseReplies(
        action: CloseAction,
        replies: List<SentTelegramMessage>,
    ) {
        when (action) {
            CloseAction.FINISH, CloseAction.CANCEL -> {
                assertTrue(replies.isEmpty())
            }

            CloseAction.CANCEL_ALL -> {
                assertEquals(1, replies.size)
                assertEquals(
                    "Все незавершённые действия отменены.\n\nЧтобы записать тренировку, используйте /training_add.",
                    replies.single().text,
                )
            }

            CloseAction.TRAINING_ADD -> {
                assertEquals(1, replies.size)
                assertEquals(WaitingDate, assertNotNull(fixture.storage.get(fixture.user)).state)
                assertEquals(
                    fixture.messages
                        .get(fixture.user)
                        ?.messageId
                        ?.value,
                    replies.single().messageId,
                )
            }
        }
    }

    internal enum class CloseAction(
        val command: String,
    ) {
        FINISH(""),
        CANCEL("/cancel"),
        CANCEL_ALL("/cancel_all"),
        TRAINING_ADD("/training_add"),
    }
}
