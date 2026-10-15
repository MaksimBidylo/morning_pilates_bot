package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.CANCEL_DELETE
import morningpilates.telegram.support.CONFIRM_DELETE
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_PREVIOUS
import morningpilates.telegram.support.DELETED_NOTICE
import morningpilates.telegram.support.DELETE_TRAINING
import morningpilates.telegram.support.DeleteViewMode
import morningpilates.telegram.support.TrainingViewDeleteTestFixture
import morningpilates.telegram.support.deliverViewCallbackOnce
import morningpilates.training.FakeTrainingRepository.DeleteCall
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDIT_REFUSED = "Bad Request: message can't be edited"
private const val MISSING_MESSAGE = "Bad Request: message to edit not found"

internal class TrainingViewDeleteFailureTest {
    private val fixture = TrainingViewDeleteTestFixture()
    private val selected get() = fixture.trainings[1]

    @Test
    fun `false never reports success discloses another record or repeats deletion on duplicated confirmation`() =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.LATEST)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.deleteResults[DeleteCall(fixture.user, selected.id)] = Result.success(false)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                val operations = fixture.repository.operations.toList()

                assertTrue(bot.deliverViewCallbackOnce(yes).replies.isEmpty())

                assertFalse(bot.currentMessage(card.messageId).text.contains(DELETED_NOTICE))
                assertFalse(bot.currentMessage(card.messageId).text.contains("Третья техника"))
                assertEquals(operations + "delete", fixture.repository.operations)
                fixture.assertDeleted(selected)
                fixture.assertStaleConfirmation(bot, yes)
                bot.sendCommandAndCollectReplies("/cancel_all", fixture.user.value)
                assertNull(fixture.base.activeView())
                fixture.assertStaleConfirmation(bot, yes)
                fixture.openCard(bot, DeleteViewMode.LATEST, position = 2)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertDeleted(selected)
            }
        }

    @Test
    fun `failed confirmation display grants no destructive action and can be opened again`() =
        runBlocking<Unit> {
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE)
                val before = assertNotNull(fixture.base.activeView())
                val delete = fixture.capture(bot, card, DELETE_TRAINING)
                val forgedYes = delete.copy(data = delete.data.replace(":delete:", ":delete-yes:"))
                bot.failNextEditMessageText(EDIT_REFUSED)

                val unchanged = fixture.press(bot, card, DELETE_TRAINING)

                assertEquals(card, unchanged)
                assertEquals(before, fixture.base.activeView())
                fixture.assertStaleConfirmation(bot, forgedYes)
                assertTrue(fixture.repository.deleteCalls.isEmpty())
                val confirmation = fixture.openConfirmation(bot, unchanged, selected)
                fixture.press(bot, confirmation, CONFIRM_DELETE)
                fixture.assertDeleted(selected)
                assertEquals(1, fixture.repository.findByDateCalls.size)
            }
        }

    @Test
    fun `accepted cancel retires yes even when Telegram cannot display the restored card`() =
        runBlocking<Unit> {
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                bot.failNextEditMessageText(EDIT_REFUSED)

                fixture.press(bot, confirmation, CANCEL_DELETE)

                assertTrue(fixture.repository.deleteCalls.isEmpty())
                assertEquals(confirmation, bot.currentMessage(card.messageId))
                val apiBeforeOldYes = bot.apiCalls.size
                assertTrue(bot.deliverViewCallbackOnce(yes).replies.isEmpty())
                assertTrue(fixture.repository.deleteCalls.isEmpty(), "Accepted cancel must retire the old confirmation")
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(apiBeforeOldYes))
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                bot.sendCommandAndCollectReplies("/training_get_last", fixture.user.value)
                fixture.assertCard(bot.currentMessage(card.messageId), DeleteViewMode.LATEST, fixture.trainings, 2)
                fixture.assertStaleConfirmation(bot, yes)
                assertTrue(fixture.repository.deleteCalls.isEmpty())
            }
        }

    @ParameterizedTest
    @EnumSource(DeleteViewMode::class)
    fun `successful delete survives result edit failure and command recovery`(mode: DeleteViewMode) =
        runBlocking<Unit> {
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, mode)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                bot.failNextEditMessageText(EDIT_REFUSED)

                fixture.press(bot, confirmation, CONFIRM_DELETE)

                fixture.assertDeleted(selected)
                fixture.assertStaleConfirmation(bot, yes)
                assertSuccessfulState(mode)
                val command = if (mode == DeleteViewMode.DATE) "/cancel" else "/cancel_all"
                bot.sendCommandAndCollectReplies(command, fixture.user.value)
                assertNull(fixture.base.activeView())
                fixture.assertStaleConfirmation(bot, yes)
                val remaining = fixture.trainings.filterNot { it.id == selected.id }
                fixture.openCard(bot, mode, position = 1, values = remaining)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertDeleted(selected)
            }
        }

    @ParameterizedTest
    @EnumSource(DeleteViewMode::class)
    fun `missing result is replaced with working buttons without another delete`(mode: DeleteViewMode) =
        runBlocking<Unit> {
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, mode)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                bot.failNextEditMessageText(MISSING_MESSAGE)
                val before = bot.apiCalls.size

                val delivered = bot.deliverViewCallbackOnce(yes)

                assertEquals(listOf("editMessageText", "sendMessage", "answerCallbackQuery"), bot.apiCalls.drop(before))
                val replacement = bot.currentMessage(TelegramMessageId(delivered.replies.single().messageId))
                assertNotEquals(card.messageId, replacement.messageId)
                assertEquals(replacement.messageId, assertNotNull(fixture.base.activeView()).messageId)
                assertTrue(replacement.text.startsWith(DELETED_NOTICE))
                assertSuccessfulState(mode)
                if (mode == DeleteViewMode.DATE) {
                    val remaining = fixture.trainings.filterNot { it.id == selected.id }
                    fixture.assertCard(replacement, mode, remaining, 1)
                    val previous = fixture.press(bot, replacement, DATE_VIEW_PREVIOUS)
                    fixture.assertCard(previous, mode, remaining, 0)
                    assertEquals(1, fixture.repository.findByDateCalls.size)
                    assertTrue(fixture.repository.findLatestCalls.isEmpty())
                } else {
                    fixture.assertCard(replacement, mode, fixture.trainings, 2)
                    fixture.press(bot, replacement, DATE_VIEW_FINISH)
                    assertNull(fixture.base.activeView())
                    assertEquals(List(2) { fixture.user }, fixture.repository.findLatestCalls)
                }
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertDeleted(selected)
            }
        }

    @Test
    fun `failed missing result replacement registers no unsent message and retains successful deletion for recovery`() =
        runBlocking<Unit> {
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                bot.failNextEditMessageText(MISSING_MESSAGE)
                bot.failNextSendMessage("Bad Request: failed to send")
                val before = bot.apiCalls.size

                assertTrue(bot.deliverViewCallbackOnce(yes).replies.isEmpty())

                assertEquals(listOf("editMessageText", "sendMessage", "answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(card.messageId, assertNotNull(fixture.base.activeView()).messageId)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertDeleted(selected)
                assertSuccessfulState(DeleteViewMode.DATE)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                bot.sendCommandAndCollectReplies("/training_get_last", fixture.user.value)
                fixture.assertCard(bot.currentMessage(card.messageId), DeleteViewMode.LATEST, fixture.trainings, 2)
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertDeleted(selected)
            }
        }

    @Test
    fun `cleanup errors clear authorization even when visible confirmation buttons remain`() =
        runBlocking<Unit> {
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                val cancel = fixture.capture(bot, confirmation, CANCEL_DELETE)
                bot.failNextDeleteMessage("Bad Request: message can't be deleted")
                bot.failNextEditMessageReplyMarkup("Bad Request: markup can't be edited")
                val before = bot.apiCalls.size

                bot.sendCommandAndCollectReplies("/cancel", fixture.user.value)

                assertEquals(
                    listOf("deleteMessage", "editMessageReplyMarkup", "deleteMessage"),
                    bot.apiCalls.drop(before),
                )
                assertFalse(bot.deleteMessageAttempts.first { it.messageId == card.messageId }.succeeded)
                assertNull(fixture.base.activeView())
                assertEquals(confirmation, bot.currentMessage(card.messageId))
                fixture.assertStaleConfirmation(bot, yes)
                fixture.assertStaleConfirmation(bot, cancel)
                fixture.openCard(bot, DeleteViewMode.LATEST)
                fixture.assertStaleConfirmation(bot, yes)
                assertTrue(fixture.repository.deleteCalls.isEmpty())
            }
        }

    private fun assertSuccessfulState(mode: DeleteViewMode) {
        val view = assertNotNull(fixture.base.activeView())
        if (mode == DeleteViewMode.DATE) {
            val search = assertNotNull(view.dateSearch)
            assertEquals(selected.details.date, search.date)
            assertEquals(fixture.trainings.filterNot { it.id == selected.id }, search.trainings)
            assertEquals(1, search.index)
        }
        assertNull(view.deletionTarget, "Successful deletion must not remain a pending destructive intent")
        assertTrue(TrainingViewAction.ConfirmDelete !in view.actions)
    }
}
