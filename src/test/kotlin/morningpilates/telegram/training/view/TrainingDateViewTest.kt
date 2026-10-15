package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.support.DATE_VIEW_CHANGE
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.DATE_VIEW_PREVIOUS
import morningpilates.telegram.support.NEXT_SEARCH_DATE
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TrainingDateViewTest {
    private val fixture = TrainingDateViewTestFixture()

    @ParameterizedTest(name = "date search displays {0} saved trainings")
    @ValueSource(ints = [0, 1, 3])
    fun `results reuse the question and show an active empty view or the shared escaped card`(count: Int) =
        runBlocking<Unit> {
            val values = fixture.trainings.take(count)
            fixture.configure(values)
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)

                val result = fixture.enterDate(bot, question)

                assertEquals(question.messageId, result.messageId)
                if (values.isEmpty()) {
                    assertEquals("За 12.10.2026 тренировок не найдено.", result.text)
                    fixture.assertButtons(result, setOf(DATE_VIEW_CHANGE, DATE_VIEW_FINISH))
                    fixture.assertQuestion(fixture.press(bot, result, DATE_VIEW_CHANGE))
                } else {
                    fixture.assertCard(result, values, 0)
                    assertTrue(result.text.contains("Triangle &lt;&amp;&gt;"))
                    assertTrue(result.text.contains("Описание &lt;first&gt;&amp;"))
                    assertTrue(result.text.contains("Заметка &lt;first&gt;&amp;"))
                }
                assertEquals(listOf(FindByDateCall(fixture.user, SEARCH_DATE)), fixture.repository.findByDateCalls)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `navigation preserves repository order and loaded snapshot without extra reads or circular buttons`() =
        runBlocking<Unit> {
            val original = fixture.trainings.toList()
            fixture.configure(original)
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                var card = fixture.enterDate(bot, question)
                fixture.assertCard(card, original, 0)
                // A later repository result must not replace the snapshot while navigating.
                val last = original.last()
                fixture.configure(listOf(last.copy(details = last.details.copy(note = "New data"))))

                card = fixture.press(bot, card, DATE_VIEW_NEXT)
                fixture.assertCard(card, original, 1)
                card = fixture.press(bot, card, DATE_VIEW_NEXT)
                fixture.assertCard(card, original, 2)
                card = fixture.press(bot, card, DATE_VIEW_PREVIOUS)
                fixture.assertCard(card, original, 1)
                card = fixture.press(bot, card, DATE_VIEW_PREVIOUS)
                fixture.assertCard(card, original, 0)

                assertEquals(question.messageId, card.messageId)
                assertTrue(bot.editedMessages.all { it.messageId == question.messageId })
                assertEquals(1, bot.sendMessageRequests.size)
                assertEquals(listOf(FindByDateCall(fixture.user, SEARCH_DATE)), fixture.repository.findByDateCalls)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest(name = "choose a new date after {0} results")
    @ValueSource(ints = [0, 3])
    fun `changing dates and repeating the same date reloads from the first card and retires old buttons`(count: Int) =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings.take(count))
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                var result = fixture.enterDate(bot, question)
                if (count > 0) result = fixture.press(bot, result, DATE_VIEW_NEXT)
                val old = bot.captureCallback(result, if (count > 0) DATE_VIEW_NEXT else DATE_VIEW_CHANGE)

                fixture.assertQuestion(fixture.press(bot, result, DATE_VIEW_CHANGE))
                val nextValues = fixture.trainings.map { it.copy(details = it.details.copy(date = NEXT_SEARCH_DATE)) }
                fixture.configure(nextValues, NEXT_SEARCH_DATE)
                result = fixture.enterDate(bot, question, "13.10.2026", 9_001L)
                fixture.assertCard(result, nextValues, 0, NEXT_SEARCH_DATE)
                val before = bot.apiCalls.size
                val stale = bot.deliverCallbackAndCollectReplies(old)
                assertTrue(stale.acknowledged)
                assertTrue(stale.replies.isEmpty())
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(result, bot.currentMessage(question.messageId))

                result = fixture.press(bot, result, DATE_VIEW_NEXT)
                fixture.assertCard(result, nextValues, 1, NEXT_SEARCH_DATE)
                fixture.assertQuestion(fixture.press(bot, result, DATE_VIEW_CHANGE))
                val refreshed = nextValues.map { it.copy(details = it.details.copy(note = "Refreshed data")) }
                fixture.configure(refreshed, NEXT_SEARCH_DATE)
                result = fixture.enterDate(bot, question, "13.10.2026", 9_002L)
                fixture.assertCard(result, refreshed, 0, NEXT_SEARCH_DATE)

                assertEquals(
                    listOf(
                        FindByDateCall(fixture.user, SEARCH_DATE),
                        FindByDateCall(fixture.user, NEXT_SEARCH_DATE),
                        FindByDateCall(fixture.user, NEXT_SEARCH_DATE),
                    ),
                    fixture.repository.findByDateCalls,
                )
                assertEquals(question.messageId, result.messageId)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                fixture.assertNoCreation()
            }
        }
}
