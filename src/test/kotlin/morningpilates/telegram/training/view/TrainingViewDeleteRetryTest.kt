package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.telegram.support.CANCEL_DELETE
import morningpilates.telegram.support.CONFIRM_DELETE
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DELETED_NOTICE
import morningpilates.telegram.support.DeleteViewMode
import morningpilates.telegram.support.EMPTY_LATEST
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingViewDeleteTestFixture
import morningpilates.training.FakeTrainingRepository.DeleteCall
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DELETE_RETRY = "Повторить удаление"
private const val DELETE_ERROR = "Не удалось удалить тренировку. Попробуйте ещё раз."
private const val READ_RETRY = "Повторить попытку"
private const val NEXT_READ_ERROR = "Тренировка удалена. Не удалось загрузить следующую тренировку."

internal class TrainingViewDeleteRetryTest {
    private val fixture = TrainingViewDeleteTestFixture()
    private val selected get() = fixture.trainings[1]
    private val failure = IllegalStateException("private PostgreSQL detail")

    @ParameterizedTest
    @EnumSource(DeleteViewMode::class)
    fun `manual retry keeps original target and retires old destructive buttons after success`(mode: DeleteViewMode) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                if (mode == DeleteViewMode.LATEST) {
                    bot.sendCommand("/training_add", fixture.user.value)
                    bot.sendTextAndCollectReplies("12.10.2026", fixture.user.value)
                }
                val draft = fixture.base.storage.get(fixture.user)
                val registration = fixture.base.messages.get(fixture.user)
                val question = registration?.let { bot.currentMessage(it.messageId) }
                val questionToken = fixture.base.messages.questionToken(fixture.user)
                val events = fixture.base.events.toList()
                val card = fixture.openCard(bot, mode)
                val original = assertNotNull(fixture.base.activeView())
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                val latest = fixture.repository.findLatestCalls.toList()
                val dates = fixture.repository.findByDateCalls.toList()
                fixture.repository.deleteResults[DeleteCall(fixture.user, selected.id)] = Result.failure(failure)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())

                val error = fixture.press(bot, confirmation, CONFIRM_DELETE)

                assertCalls(1)
                assertEquals(latest, fixture.repository.findLatestCalls)
                assertEquals(dates, fixture.repository.findByDateCalls)
                assertError(error)
                assertEquals(original.dateSearch, assertNotNull(fixture.base.activeView()).dateSearch)
                assertEquals(selected, assertNotNull(fixture.base.activeView()).deletionTarget)
                fixture.assertStaleConfirmation(bot, yes)
                val retry = fixture.capture(bot, error, DELETE_RETRY)
                fixture.allowDelete(selected)
                if (mode == DeleteViewMode.LATEST) {
                    fixture.repository.findLatestResults[fixture.user] = Result.failure(failure)
                }

                val result = fixture.press(bot, error, DELETE_RETRY)

                assertCalls(2)
                assertTrue(result.text.startsWith(DELETED_NOTICE))
                assertEquals(card.messageId, result.messageId)
                assertEquals(dates, fixture.repository.findByDateCalls)
                if (mode == DeleteViewMode.LATEST) {
                    assertEquals(latest + fixture.user, fixture.repository.findLatestCalls)
                    assertLatestReadRecovery(bot, result, yes, retry)
                    assertEquals(latest + List(2) { fixture.user }, fixture.repository.findLatestCalls)
                } else {
                    assertEquals(latest, fixture.repository.findLatestCalls)
                    fixture.assertCard(result, mode, fixture.trainings.filterNot { it.id == selected.id }, 1)
                }
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertStaleConfirmation(bot, retry)
                assertEquals(if (mode == DeleteViewMode.LATEST) 2 else 1, bot.sendMessageRequests.size)
                assertEquals(draft, fixture.base.storage.get(fixture.user))
                assertEquals(registration, fixture.base.messages.get(fixture.user))
                if (registration != null) assertEquals(question, bot.currentMessage(registration.messageId))
                assertEquals(questionToken, fixture.base.messages.questionToken(fixture.user))
                assertEquals(events, fixture.base.events)
                assertTrue(fixture.repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `each current retry performs one deletion across repeated failures without losing date`() =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val dateView = assertNotNull(fixture.base.activeView()).dateSearch
                fixture.repository.deleteResults[DeleteCall(fixture.user, selected.id)] = Result.failure(failure)
                val first = fixture.press(bot, confirmation, CONFIRM_DELETE)
                assertError(first)
                assertCalls(1)
                val oldRetry = fixture.capture(bot, first, DELETE_RETRY)

                val second = fixture.press(bot, first, DELETE_RETRY)

                assertError(second)
                assertCalls(2)
                assertEquals(dateView, assertNotNull(fixture.base.activeView()).dateSearch)
                assertEquals(selected, assertNotNull(fixture.base.activeView()).deletionTarget)
                fixture.allowDelete(selected)
                val result = fixture.press(bot, second, DELETE_RETRY)
                assertCalls(3)
                fixture.assertStaleConfirmation(bot, oldRetry)
                fixture.assertCard(result, DeleteViewMode.DATE, fixture.trainings.filterNot { it.id == selected.id }, 1)
                assertEquals(1, fixture.repository.findByDateCalls.size)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.base.assertNoCreation()
            }
        }

    @ParameterizedTest
    @EnumSource(DeleteViewMode::class)
    fun `cancel after failure restores card and retires old buttons across reopening`(mode: DeleteViewMode) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, mode)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.deleteResults[DeleteCall(fixture.user, selected.id)] = Result.failure(failure)
                val error = fixture.press(bot, confirmation, CONFIRM_DELETE)
                assertError(error)
                val retry = fixture.capture(bot, error, DELETE_RETRY)
                val latest = fixture.repository.findLatestCalls.toList()
                val dates = fixture.repository.findByDateCalls.toList()

                val restored = fixture.press(bot, error, CANCEL_DELETE)

                assertEquals(card.text, restored.text)
                fixture.assertCard(restored, mode, fixture.trainings, 1)
                assertCalls(1)
                assertEquals(latest, fixture.repository.findLatestCalls)
                assertEquals(dates, fixture.repository.findByDateCalls)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertStaleConfirmation(bot, retry)
                fixture.openConfirmation(bot, restored, selected)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertStaleConfirmation(bot, retry)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @ParameterizedTest
    @EnumSource(ErrorCommand::class)
    fun `commands retire delete error retry without another deletion`(action: ErrorCommand) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.deleteResults[DeleteCall(fixture.user, selected.id)] = Result.failure(failure)
                val error = fixture.press(bot, confirmation, CONFIRM_DELETE)
                assertError(error)
                val retry = fixture.capture(bot, error, DELETE_RETRY)
                val dates = fixture.repository.findByDateCalls.toList()

                bot.sendCommandAndCollectReplies(action.command, fixture.user.value)

                assertNull(fixture.base.activeView())
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertStaleConfirmation(bot, retry)
                assertCalls(1)
                assertEquals(dates, fixture.repository.findByDateCalls)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                if (action == ErrorCommand.ADD) assertNotNull(fixture.base.storage.get(fixture.user))
            }
        }

    @ParameterizedTest
    @EnumSource(ReadRecovery::class)
    fun `read retries after delete never repeat deletion across failures`(outcome: ReadRecovery) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.LATEST)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.allowDelete(selected)
                fixture.repository.findLatestResults[fixture.user] = Result.failure(failure)

                val readError = fixture.press(bot, confirmation, CONFIRM_DELETE)

                assertEquals(NEXT_READ_ERROR, readError.text)
                fixture.base.assertButtons(readError, setOf(READ_RETRY, DATE_VIEW_FINISH))
                assertCalls(1)
                assertEquals(listOf("findLatest", "delete", "findLatest"), fixture.repository.operations)
                val repeatedError = fixture.press(bot, readError, READ_RETRY)
                assertEquals(NEXT_READ_ERROR, repeatedError.text)
                fixture.base.assertButtons(repeatedError, setOf(READ_RETRY, DATE_VIEW_FINISH))
                assertCalls(1)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.repository.findLatestResults[fixture.user] =
                    Result.success(if (outcome == ReadRecovery.CARD) fixture.trainings.last() else null)

                val recovered = fixture.press(bot, repeatedError, READ_RETRY)

                assertCalls(1)
                assertEquals(List(4) { fixture.user }, fixture.repository.findLatestCalls)
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                if (outcome == ReadRecovery.CARD) {
                    fixture.assertCard(recovered, DeleteViewMode.LATEST, fixture.trainings, 2)
                    val operations = fixture.repository.operations.toList()
                    fixture.press(bot, recovered, DATE_VIEW_FINISH)
                    assertNull(fixture.base.activeView())
                    assertEquals(operations, fixture.repository.operations)
                } else {
                    assertTrue(recovered.text.endsWith(EMPTY_LATEST))
                    fixture.base.assertButtons(recovered, emptySet())
                    val operations = fixture.repository.operations.toList()
                    bot.sendCommandAndCollectReplies("/cancel", fixture.user.value)
                    assertNull(fixture.base.activeView())
                    assertEquals(operations, fixture.repository.operations)
                }
                fixture.assertStaleConfirmation(bot, yes)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    private suspend fun assertLatestReadRecovery(
        bot: TestTelegramBot,
        error: EditedTelegramMessage,
        oldYes: CallbackQuery,
        oldDeleteRetry: CallbackQuery,
    ) {
        assertEquals(NEXT_READ_ERROR, error.text)
        fixture.base.assertButtons(error, setOf(READ_RETRY, DATE_VIEW_FINISH))
        fixture.assertStaleConfirmation(bot, oldYes)
        fixture.assertStaleConfirmation(bot, oldDeleteRetry)
        fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
        val recovered = fixture.press(bot, error, READ_RETRY)
        fixture.assertCard(recovered, DeleteViewMode.LATEST, fixture.trainings, 2)
        assertEquals(
            listOf("findLatest", "delete", "delete", "findLatest", "findLatest"),
            fixture.repository.operations,
        )
    }

    private fun assertError(message: EditedTelegramMessage) {
        assertEquals(DELETE_ERROR, message.text)
        fixture.base.assertButtons(message, setOf(DELETE_RETRY, CANCEL_DELETE))
        assertFalse(message.text.contains(failure.message!!))
    }

    private fun assertCalls(count: Int) {
        assertEquals(List(count) { DeleteCall(fixture.user, selected.id) }, fixture.repository.deleteCalls)
        assertTrue(fixture.repository.saveCalls.isEmpty())
    }

    internal enum class ErrorCommand(
        val command: String,
    ) {
        CANCEL("/cancel"),
        CANCEL_ALL("/cancel_all"),
        ADD("/training_add"),
    }

    internal enum class ReadRecovery { CARD, EMPTY }
}
