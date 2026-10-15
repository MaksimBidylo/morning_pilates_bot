package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.telegram.support.assertIgnoredViewCallback
import morningpilates.telegram.support.deliverViewCallbackOnce
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PREVIOUS_LAUNCH_NOTICE = "Этот диалог больше неактуален. Используйте меню команд."

internal class TrainingDateViewRestartTest {
    @ParameterizedTest
    @EnumSource(PreviousScreen::class)
    fun `a previous launch button is only acknowledged before and after a fresh date search`(screen: PreviousScreen) =
        runBlocking<Unit> {
            val (oldMessage, oldCallback) = previousLaunch(screen)
            val restarted = TrainingDateViewTestFixture()
            restarted.configure(restarted.trainings)
            restarted.bot(firstBotMessageId = 8_351L, existingMessages = listOf(oldMessage)).use { bot ->
                assertTrue(bot.apiCalls.isEmpty())

                restarted.assertIgnoredViewCallback(bot, oldCallback, PREVIOUS_LAUNCH_NOTICE)

                assertEquals(oldMessage, bot.currentMessage(oldMessage.messageId))
                assertTrue(restarted.repository.findByDateCalls.isEmpty())
                restarted.assertNoCreation()
                val question = restarted.openQuestion(bot)
                restarted.assertIgnoredViewCallback(bot, oldCallback, PREVIOUS_LAUNCH_NOTICE)
                assertEquals(question, bot.currentMessage(question.messageId))
                val result = restarted.enterDate(bot, question)
                restarted.assertCard(result, restarted.trainings, 0)
                restarted.assertIgnoredViewCallback(bot, oldCallback, PREVIOUS_LAUNCH_NOTICE)
                assertEquals(result, bot.currentMessage(result.messageId))
                assertEquals(oldMessage, bot.currentMessage(oldMessage.messageId))
                val next = bot.captureCallback(result, DATE_VIEW_NEXT, restarted.user)
                assertTrue(bot.deliverViewCallbackOnce(next).replies.isEmpty())
                restarted.assertCard(bot.currentMessage(result.messageId), restarted.trainings, 1)
                assertEquals(listOf(FindByDateCall(restarted.user, SEARCH_DATE)), restarted.repository.findByDateCalls)
                assertTrue(restarted.repository.findLatestCalls.isEmpty())
                assertEquals(1, bot.sendMessageRequests.size)
                restarted.assertNoCreation()
            }
        }

    private suspend fun previousLaunch(screen: PreviousScreen): Pair<EditedTelegramMessage, CallbackQuery> {
        val previous = TrainingDateViewTestFixture()
        if (screen == PreviousScreen.ERROR) {
            previous.repository.findByDateResults[FindByDateCall(previous.user, SEARCH_DATE)] =
                Result.failure(IllegalStateException("Unavailable before restart"))
        } else {
            previous.configure(previous.trainings)
        }
        previous.bot(firstBotMessageId = 6_341L).use { bot ->
            val question = previous.openQuestion(bot)
            val message = if (screen == PreviousScreen.WAITING) question else previous.enterDate(bot, question)
            val callback = bot.captureCallback(message, screen.button, previous.user)
            previous.assertNoCreation()
            return message to callback
        }
    }

    internal enum class PreviousScreen(
        val button: String,
    ) {
        WAITING(DATE_VIEW_FINISH),
        RESULTS(DATE_VIEW_NEXT),
        ERROR("Повторить попытку"),
    }
}
