package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DATE_VIEW_CHANGE
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.DATE_VIEW_PREVIOUS
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.telegram.support.assertIgnoredViewCallback
import morningpilates.telegram.support.deliverViewCallbackOnce
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDIT_REFUSED = "Bad Request: message can't be edited"
private const val MISSING_VIEW = "Bad Request: message to edit not found"
private const val DATE_RETRY = "Повторить попытку"

internal class TrainingDateViewDisplayFailureTest {
    private val fixture = TrainingDateViewTestFixture()

    @Test
    fun `failed first question leaves command and permits a fresh working search`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                bot.failNextSendMessage("Bad Request: failed to send")

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date", messageId = 2_000L).isEmpty())

                assertEquals(listOf("sendMessage"), bot.apiCalls)
                assertTrue(bot.deleteMessageAttempts.isEmpty())
                assertReads(0)
                val question = fixture.openQuestion(bot)
                assertTrue(bot.editedMessages.isEmpty(), "An unsent question must not be registered for editing")
                fixture.assertCard(fixture.enterDate(bot, question), fixture.trainings, 0)
                assertReads(1)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `failed result edit preserves date input and waiting question until resubmission`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                bot.failNextEditMessageText(EDIT_REFUSED)
                val before = bot.apiCalls.size

                val unchanged = fixture.enterDate(bot, question, messageId = 9_000L)

                assertEquals(question, unchanged)
                assertEquals(listOf("editMessageText"), bot.apiCalls.drop(before))
                assertInputRetained(bot, 9_000L)
                assertReads(1)
                fixture.assertCard(fixture.enterDate(bot, question, messageId = 9_001L), fixture.trainings, 0)
                assertInputDeleted(bot, 9_001L)
                assertReads(2)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `failed navigation leaves position and button usable and processed callback cannot advance twice`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val first = fixture.enterDate(bot, fixture.openQuestion(bot))
                val next = bot.captureCallback(first, DATE_VIEW_NEXT, fixture.user)
                bot.failNextEditMessageText(EDIT_REFUSED)
                val before = bot.apiCalls.size

                assertTrue(bot.deliverViewCallbackOnce(next).replies.isEmpty())

                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(first, bot.currentMessage(first.messageId))
                assertReads(1)
                assertTrue(bot.deliverViewCallbackOnce(next).replies.isEmpty())
                val second = bot.currentMessage(first.messageId)
                fixture.assertCard(second, fixture.trainings, 1)
                fixture.assertIgnoredViewCallback(bot, next)
                assertEquals(second, bot.currentMessage(second.messageId))
                assertReads(1)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `failed retry display preserves error button and date for another load`() =
        runBlocking<Unit> {
            fixture.repository.findByDateResults[FindByDateCall(fixture.user, SEARCH_DATE)] =
                Result.failure(IllegalStateException("Unavailable"))
            fixture.bot().use { bot ->
                val error = fixture.enterDate(bot, fixture.openQuestion(bot))
                assertEquals("Не удалось загрузить тренировки. Попробуйте ещё раз.", error.text)
                val retry = bot.captureCallback(error, DATE_RETRY, fixture.user)
                fixture.configure(fixture.trainings)
                bot.failNextEditMessageText(EDIT_REFUSED)
                val before = bot.apiCalls.size

                assertTrue(bot.deliverViewCallbackOnce(retry).replies.isEmpty())

                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(error, bot.currentMessage(error.messageId))
                assertReads(2)
                assertTrue(bot.deliverViewCallbackOnce(retry).replies.isEmpty())
                val recovered = bot.currentMessage(error.messageId)
                fixture.assertCard(recovered, fixture.trainings, 0)
                assertReads(3)
                fixture.assertIgnoredViewCallback(bot, retry)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `missing question is replaced and input is deleted only after replacement is sent and registered`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                val old = bot.captureCallback(question, DATE_VIEW_FINISH, fixture.user)
                bot.failNextEditMessageText(MISSING_VIEW)
                var sent = false
                bot.beforeNextSendMessageResponse {
                    assertInputRetained(bot, 9_000L)
                    sent = true
                }
                bot.beforeNextDeleteMessageResponse {
                    assertNotEquals(question.messageId, assertNotNull(fixture.activeView()).messageId)
                    assertTrue(sent)
                }
                val before = bot.apiCalls.size

                val replies = bot.sendTextAndCollectReplies("12.10.2026", messageId = 9_000L)

                assertTrue(sent)
                assertEquals(listOf("editMessageText", "sendMessage", "deleteMessage"), bot.apiCalls.drop(before))
                assertEquals(1, replies.size)
                val replacement = bot.currentMessage(TelegramMessageId(replies.single().messageId))
                assertNotEquals(question.messageId, replacement.messageId)
                fixture.assertCard(replacement, fixture.trainings, 0)
                assertInputDeleted(bot, 9_000L)
                fixture.assertIgnoredViewCallback(bot, old)
                val next = bot.captureCallback(replacement, DATE_VIEW_NEXT, fixture.user)
                assertTrue(bot.deliverViewCallbackOnce(next).replies.isEmpty())
                fixture.assertCard(bot.currentMessage(replacement.messageId), fixture.trainings, 1)
                assertReads(1)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `missing card is replaced at the requested position without reloading its snapshot`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val first = fixture.enterDate(bot, fixture.openQuestion(bot))
                val next = bot.captureCallback(first, DATE_VIEW_NEXT, fixture.user)
                fixture.configure(emptyList())
                bot.failNextEditMessageText(MISSING_VIEW)
                val before = bot.apiCalls.size

                val delivered = bot.deliverViewCallbackOnce(next)

                assertEquals(listOf("editMessageText", "sendMessage", "answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(1, delivered.replies.size)
                val replacement = bot.currentMessage(TelegramMessageId(delivered.replies.single().messageId))
                assertNotEquals(first.messageId, replacement.messageId)
                fixture.assertCard(replacement, fixture.trainings, 1)
                fixture.assertIgnoredViewCallback(bot, next)
                val previous = bot.captureCallback(replacement, DATE_VIEW_PREVIOUS, fixture.user)
                assertTrue(bot.deliverViewCallbackOnce(previous).replies.isEmpty())
                fixture.assertCard(bot.currentMessage(replacement.messageId), fixture.trainings, 0)
                assertReads(1)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `failed missing message replacement leaves input and allows command recovery`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                bot.failNextEditMessageText(MISSING_VIEW)
                bot.failNextSendMessage("Bad Request: failed to send")
                val before = bot.apiCalls.size

                assertTrue(bot.sendTextAndCollectReplies("12.10.2026", messageId = 9_000L).isEmpty())

                assertEquals(listOf("editMessageText", "sendMessage"), bot.apiCalls.drop(before))
                assertInputRetained(bot, 9_000L)
                assertReads(1)
                bot.failNextEditMessageText(MISSING_VIEW)
                val recovered = bot.sendCommandAndCollectReplies("/training_get_date")
                assertEquals(1, recovered.size)
                val fresh = bot.currentMessage(TelegramMessageId(recovered.single().messageId))
                fixture.assertQuestion(fresh)
                assertNotEquals(question.messageId, fresh.messageId)
                fixture.assertCard(fixture.enterDate(bot, fresh, messageId = 9_001L), fixture.trainings, 0)
                assertReads(2)
                assertInputDeleted(bot, 9_001L)
                fixture.assertNoCreation()
            }
        }

    private fun assertReads(count: Int) {
        assertEquals(List(count) { FindByDateCall(fixture.user, SEARCH_DATE) }, fixture.repository.findByDateCalls)
        assertTrue(fixture.repository.findLatestCalls.isEmpty())
    }

    private fun assertInputRetained(
        bot: TestTelegramBot,
        id: Long,
    ) {
        assertTrue(bot.deleteMessageAttempts.none { it.messageId == TelegramMessageId(id) })
    }

    private fun assertInputDeleted(
        bot: TestTelegramBot,
        id: Long,
    ) {
        assertTrue(TelegramDeleteAttempt(fixture.chat, TelegramMessageId(id), true) in bot.deleteMessageAttempts)
    }
}
