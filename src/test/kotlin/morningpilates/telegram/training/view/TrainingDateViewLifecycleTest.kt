package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.TrainingFlowState.WaitingDate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingDateViewLifecycleTest {
    companion object {
        @JvmStatic
        fun closingCases(): List<Arguments> =
            listOf(false, true).flatMap { results -> CloseAction.entries.map { Arguments.of(results, it) } }
    }

    private val fixture = TrainingDateViewTestFixture()

    @ParameterizedTest
    @EnumSource(ReturnToLast::class)
    fun `latest and date search share one message and retire search input`(scenario: ReturnToLast) =
        runBlocking<Unit> {
            val latest = fixture.trainings.first()
            fixture.repository.findLatestResults[fixture.user] = Result.success(latest)
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val sent = bot.sendCommand("/training_get_last")
                val id = TelegramMessageId(sent.messageId)

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date").isEmpty())
                val question = bot.currentMessage(id)
                fixture.assertQuestion(question)
                val oldNavigation =
                    if (scenario != ReturnToLast.WAITING_WITH_CARD) {
                        val result = fixture.enterDate(bot, question)
                        fixture.assertCard(result, fixture.trainings, 0)
                        bot.captureCallback(result, DATE_VIEW_NEXT)
                    } else {
                        null
                    }
                val emptyDiary = scenario == ReturnToLast.RESULTS_WITH_EMPTY_DIARY
                fixture.repository.findLatestResults[fixture.user] = Result.success(if (emptyDiary) null else latest)

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last").isEmpty())

                val last = bot.currentMessage(id)
                if (emptyDiary) {
                    assertTrue(last.text.startsWith("Тренировок пока нет."))
                    fixture.assertButtons(last, emptySet())
                } else {
                    assertTrue(last.text.startsWith("Последняя тренировка"))
                    fixture.assertButtons(last, setOf("Удалить тренировку", DATE_VIEW_FINISH))
                }
                if (oldNavigation != null) {
                    val before = bot.apiCalls.size
                    assertTrue(bot.deliverCallbackAndCollectReplies(oldNavigation).acknowledged)
                    assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                    assertEquals(last, bot.currentMessage(id))
                }
                assertEquals(1, bot.sendMessageRequests.size)
                val reads = fixture.repository.findByDateCalls.toList()
                bot.sendTextAndCollectReplies("12.10.2026", messageId = 9_001L)
                assertEquals(
                    reads,
                    fixture.repository.findByDateCalls,
                    "Latest viewing must stop consuming search dates",
                )
                assertEquals(last, bot.currentMessage(id))
                assertEquals(listOf(fixture.user, fixture.user), fixture.repository.findLatestCalls)
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest(name = "results = {0}, close with {1}")
    @MethodSource("closingCases")
    fun `closing waiting or results retires callbacks and allows a clean new date search`(
        withResults: Boolean,
        action: CloseAction,
    ) = runBlocking<Unit> {
        fixture.configure(fixture.trainings)
        fixture.bot().use { bot ->
            val question = fixture.openQuestion(bot)
            val active = if (withResults) fixture.enterDate(bot, question) else question
            val old = bot.captureCallback(active, DATE_VIEW_FINISH)
            val reads = fixture.repository.findByDateCalls.toList()
            val sends = bot.sendMessageRequests.size
            val replies =
                when (action) {
                    CloseAction.FINISH -> {
                        val result = bot.deliverCallbackAndCollectReplies(old)
                        assertTrue(result.acknowledged)
                        result.replies
                    }

                    CloseAction.CANCEL -> {
                        bot.sendCommandAndCollectReplies("/cancel")
                    }

                    CloseAction.CANCEL_ALL -> {
                        bot.sendCommandAndCollectReplies("/cancel_all")
                    }

                    CloseAction.TRAINING_ADD -> {
                        bot.sendCommandAndCollectReplies("/training_add")
                    }
                }
            assertTrue(TelegramDeleteAttempt(fixture.chat, active.messageId, true) in bot.deleteMessageAttempts)
            assertCloseReplies(action, active, replies)
            val newMessages = if (action == CloseAction.CANCEL_ALL || action == CloseAction.TRAINING_ADD) 1 else 0
            assertEquals(sends + newMessages, bot.sendMessageRequests.size)
            assertEquals(reads, fixture.repository.findByDateCalls)
            val session = fixture.storage.get(fixture.user)
            val before = bot.apiCalls.size
            assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
            assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
            assertEquals(session, fixture.storage.get(fixture.user))
            if (action == CloseAction.TRAINING_ADD) {
                val created = bot.currentMessage(assertNotNull(fixture.messages.get(fixture.user)).messageId)
                val cancelled = bot.pressInlineButtonAndCollectReplies(created, "Отменить тренировку")
                assertTrue(cancelled.acknowledged)
            }
            assertTrue(fixture.repository.saveCalls.isEmpty())
            val events = fixture.events.toList()
            val fresh = fixture.openQuestion(bot)
            assertNotEquals(active.messageId, fresh.messageId)
            assertEquals(reads, fixture.repository.findByDateCalls)
            val calls = bot.apiCalls.size
            assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
            assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
            assertEquals(fresh, bot.currentMessage(fresh.messageId))
            val result = fixture.enterDate(bot, fresh, messageId = 9_002L)
            fixture.assertCard(result, fixture.trainings, 0)
            assertEquals(reads + FindByDateCall(fixture.user, SEARCH_DATE), fixture.repository.findByDateCalls)
            assertTrue(fixture.repository.findLatestCalls.isEmpty())
            assertTrue(fixture.repository.saveCalls.isEmpty())
            assertNull(fixture.storage.get(fixture.user))
            assertNull(fixture.messages.get(fixture.user))
            assertEquals(events, fixture.events)
        }
    }

    private fun assertCloseReplies(
        action: CloseAction,
        active: EditedTelegramMessage,
        replies: List<SentTelegramMessage>,
    ) {
        if (action == CloseAction.CANCEL_ALL) {
            assertEquals(1, replies.size)
            assertEquals(
                "Все незавершённые действия отменены.\n\nЧтобы записать тренировку, используйте /training_add.",
                replies.single().text,
            )
            assertNotEquals(active.messageId.value, replies.single().messageId)
        } else if (action == CloseAction.TRAINING_ADD) {
            assertEquals(1, replies.size)
            assertEquals(WaitingDate, assertNotNull(fixture.storage.get(fixture.user)).state)
        } else {
            assertTrue(replies.isEmpty())
        }
    }

    internal enum class CloseAction { FINISH, CANCEL, CANCEL_ALL, TRAINING_ADD }

    internal enum class ReturnToLast { WAITING_WITH_CARD, RESULTS_WITH_CARD, RESULTS_WITH_EMPTY_DIARY }
}
