package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.support.CONFIRM_DELETE
import morningpilates.telegram.support.DELETED_NOTICE
import morningpilates.telegram.support.DeleteViewMode
import morningpilates.telegram.support.EMPTY_LATEST
import morningpilates.telegram.support.TrainingViewDeleteTestFixture
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TrainingLatestViewDeleteTest {
    private val fixture = TrainingViewDeleteTestFixture()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `latest deletion fixes the displayed id then reads the remaining diary`(empty: Boolean) =
        runBlocking<Unit> {
            val selected = fixture.trainings[1]
            val next = fixture.trainings.last()
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, DeleteViewMode.LATEST)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val oldYes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.findLatestResults[fixture.user] = Result.success(if (empty) null else next)
                val before = fixture.repository.operations.size

                val current = fixture.press(bot, confirmation, CONFIRM_DELETE)

                fixture.assertDeleted(selected)
                assertEquals(listOf("delete", "findLatest"), fixture.repository.operations.drop(before))
                assertEquals(listOf(fixture.user, fixture.user), fixture.repository.findLatestCalls)
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                assertEquals(card.messageId, current.messageId)
                assertTrue(current.text.contains(DELETED_NOTICE))
                if (empty) {
                    assertTrue(current.text.contains(EMPTY_LATEST))
                    fixture.base.assertButtons(current, emptySet())
                } else {
                    fixture.assertCard(current, DeleteViewMode.LATEST, listOf(next), 0)
                }
                fixture.assertStaleConfirmation(bot, oldYes)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.base.assertNoCreation()
            }
        }
}
