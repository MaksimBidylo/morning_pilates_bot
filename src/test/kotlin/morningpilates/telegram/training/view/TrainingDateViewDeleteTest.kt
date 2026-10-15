package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.support.CONFIRM_DELETE
import morningpilates.telegram.support.DATE_VIEW_CHANGE
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.DATE_VIEW_PREVIOUS
import morningpilates.telegram.support.DELETED_NOTICE
import morningpilates.telegram.support.DeleteViewMode
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingViewDeleteTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TrainingDateViewDeleteTest {
    private val fixture = TrainingViewDeleteTestFixture()

    @ParameterizedTest
    @EnumSource(DeletedPosition::class)
    fun `confirmed deletion removes only selected id from the loaded date snapshot`(scenario: DeletedPosition) =
        runBlocking<Unit> {
            val original = if (scenario == DeletedPosition.SOLE) fixture.trainings.take(1) else fixture.trainings
            val position = scenario.position
            val selected = original[position]
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.DATE, position, original)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val yes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                // Subsequent reads would return a different diary; navigation must still use the loaded snapshot.
                if (scenario == DeletedPosition.FIRST) {
                    val newTraining =
                        original.last().copy(
                            id = TrainingId(UUID(92L, 999L)),
                            details = original.last().details.copy(note = "Completely new diary"),
                        )
                    fixture.base.configure(listOf(newTraining))
                }

                val current = fixture.press(bot, confirmation, CONFIRM_DELETE)

                fixture.assertDeleted(selected)
                assertEquals(card.messageId, current.messageId)
                assertTrue(current.text.contains(DELETED_NOTICE))
                val remaining = original.filterNot { it.id == selected.id }
                if (remaining.isEmpty()) {
                    assertTrue(current.text.contains("За 12.10.2026 тренировок не найдено."))
                    fixture.base.assertButtons(current, setOf(DATE_VIEW_CHANGE, DATE_VIEW_FINISH))
                } else {
                    val nextPosition = minOf(position, remaining.lastIndex)
                    fixture.assertCard(current, DeleteViewMode.DATE, remaining, nextPosition)
                    assertRemainingNavigation(bot, current, remaining, nextPosition)
                }
                fixture.assertStaleConfirmation(bot, yes)
                assertEquals(listOf(FindByDateCall(fixture.user, SEARCH_DATE)), fixture.repository.findByDateCalls)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.base.assertNoCreation()
            }
        }

    private suspend fun assertRemainingNavigation(
        bot: TestTelegramBot,
        first: EditedTelegramMessage,
        remaining: List<SavedTraining>,
        position: Int,
    ) {
        var card = first
        var index = position
        while (index > 0) {
            card = fixture.press(bot, card, DATE_VIEW_PREVIOUS)
            index--
            fixture.assertCard(card, DeleteViewMode.DATE, remaining, index)
        }
        while (index < remaining.lastIndex) {
            card = fixture.press(bot, card, DATE_VIEW_NEXT)
            index++
            fixture.assertCard(card, DeleteViewMode.DATE, remaining, index)
        }
    }

    internal enum class DeletedPosition(
        val position: Int,
    ) {
        FIRST(0),
        MIDDLE(1),
        LAST(2),
        SOLE(0),
    }
}
