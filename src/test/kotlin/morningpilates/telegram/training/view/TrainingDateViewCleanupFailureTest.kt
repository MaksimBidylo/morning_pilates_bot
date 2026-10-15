package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.telegram.support.assertIgnoredViewCallback
import morningpilates.telegram.support.deliverViewCallbackOnce
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.TrainingFlowState.WaitingDate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingDateViewCleanupFailureTest {
    private val fixture = TrainingDateViewTestFixture()

    @ParameterizedTest
    @EnumSource(CleanupCase::class)
    fun `cleanup failure retires authorization and permits the next action`(scenario: CleanupCase) =
        runBlocking<Unit> {
            configure(scenario)
            fixture.bot().use { bot ->
                val active = fixture.enterDate(bot, fixture.openQuestion(bot))
                val old = bot.captureCallback(active, scenario.oldButton, fixture.user)
                bot.failNextDeleteMessage("Bad Request: message can't be deleted")
                if (scenario.markupFails) bot.failNextEditMessageReplyMarkup("Bad Request: message can't be edited")
                var registrationChecked = false
                bot.beforeNextDeleteMessageResponse {
                    assertNull(fixture.activeView(), "Registration must be removed before cleanup is attempted")
                    registrationChecked = true
                }
                val before = bot.apiCalls.size

                val replies = close(bot, active, scenario)

                assertTrue(registrationChecked)
                assertEquals(scenario.expectedCalls, bot.apiCalls.drop(before))
                assertTrue(TelegramDeleteAttempt(fixture.chat, active.messageId, false) in bot.deleteMessageAttempts)
                val remaining = bot.currentMessage(active.messageId)
                if (scenario.markupFails) {
                    assertEquals(active, remaining)
                } else {
                    assertTrue(remaining.inlineKeyboard.isEmpty())
                }
                assertCloseResult(scenario, replies)
                fixture.assertIgnoredViewCallback(bot, old)
                if (scenario == CleanupCase.ERROR_ADD) {
                    val created = bot.currentMessage(assertNotNull(fixture.messages.get(fixture.user)).messageId)
                    val cancel = bot.captureCallback(created, "Отменить тренировку", fixture.user)
                    assertTrue(bot.deliverViewCallbackOnce(cancel).replies.isEmpty())
                }
                val events = fixture.events.toList()
                fixture.configure(fixture.trainings)
                val fresh = fixture.openQuestion(bot)
                assertNotEquals(active.messageId, fresh.messageId)
                fixture.assertIgnoredViewCallback(bot, old)
                assertEquals(fresh, bot.currentMessage(fresh.messageId))
                fixture.assertCard(fixture.enterDate(bot, fresh, messageId = 9_001L), fixture.trainings, 0)
                assertEquals(List(2) { FindByDateCall(fixture.user, SEARCH_DATE) }, fixture.repository.findByDateCalls)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                assertEquals(events, fixture.events)
                assertNull(fixture.storage.get(fixture.user))
                assertNull(fixture.messages.get(fixture.user))
                assertTrue(fixture.repository.saveCalls.isEmpty())
            }
        }

    private fun configure(scenario: CleanupCase) {
        if (scenario == CleanupCase.RESULT_CANCEL) {
            fixture.configure(fixture.trainings)
        } else if (scenario == CleanupCase.EMPTY_FINISH) {
            fixture.configure(emptyList())
        } else {
            fixture.repository.findByDateResults[FindByDateCall(fixture.user, SEARCH_DATE)] =
                Result.failure(IllegalStateException("Unavailable"))
        }
    }

    private suspend fun close(
        bot: TestTelegramBot,
        active: EditedTelegramMessage,
        scenario: CleanupCase,
    ): List<SentTelegramMessage> =
        if (scenario == CleanupCase.EMPTY_FINISH) {
            bot.deliverViewCallbackOnce(bot.captureCallback(active, DATE_VIEW_FINISH, fixture.user)).replies
        } else {
            bot.sendCommandAndCollectReplies(scenario.command, fixture.user.value)
        }

    private fun assertCloseResult(
        scenario: CleanupCase,
        replies: List<SentTelegramMessage>,
    ) {
        when (scenario) {
            CleanupCase.RESULT_CANCEL, CleanupCase.EMPTY_FINISH -> {
                assertTrue(replies.isEmpty())
            }

            CleanupCase.ERROR_CANCEL_ALL -> {
                assertEquals(1, replies.size)
                assertEquals(
                    "Все незавершённые действия отменены.\n\nЧтобы записать тренировку, используйте /training_add.",
                    replies.single().text,
                )
            }

            CleanupCase.ERROR_ADD -> {
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

    internal enum class CleanupCase(
        val command: String,
        val oldButton: String,
        val markupFails: Boolean,
        val expectedCalls: List<String>,
    ) {
        RESULT_CANCEL(
            "/cancel",
            DATE_VIEW_NEXT,
            false,
            listOf("deleteMessage", "editMessageReplyMarkup", "deleteMessage"),
        ),
        ERROR_ADD(
            "/training_add",
            "Повторить попытку",
            true,
            listOf("deleteMessage", "editMessageReplyMarkup", "sendMessage", "deleteMessage"),
        ),
        ERROR_CANCEL_ALL(
            "/cancel_all",
            "Повторить попытку",
            false,
            listOf("deleteMessage", "editMessageReplyMarkup", "sendMessage", "deleteMessage"),
        ),
        EMPTY_FINISH(
            "",
            DATE_VIEW_FINISH,
            false,
            listOf("deleteMessage", "editMessageReplyMarkup", "answerCallbackQuery"),
        ),
    }
}
