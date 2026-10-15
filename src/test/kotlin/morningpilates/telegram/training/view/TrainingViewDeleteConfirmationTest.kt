package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.CANCEL_DELETE
import morningpilates.telegram.support.CONFIRM_DELETE
import morningpilates.telegram.support.DELETE_TRAINING
import morningpilates.telegram.support.DeleteViewMode
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingViewDeleteTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.TrainingFlowState.WaitingDate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingViewDeleteConfirmationTest {
    private val fixture = TrainingViewDeleteTestFixture()

    @ParameterizedTest
    @EnumSource(DeleteViewMode::class)
    fun `view cards offer deletion and open confirmation for exactly the displayed training`(mode: DeleteViewMode) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, mode)

                val confirmation = fixture.openConfirmation(bot, card, fixture.trainings[1])

                assertEquals(card.messageId, confirmation.messageId)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(fixture.repository.deleteCalls.isEmpty())
                fixture.base.assertNoCreation()
            }
        }

    @ParameterizedTest
    @EnumSource(DeleteViewMode::class)
    fun `cancel restores card and retires confirmation across reopening`(mode: DeleteViewMode) =
        runBlocking<Unit> {
            val selected = fixture.trainings[1]
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                val card = fixture.openCard(bot, mode)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                val oldYes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                val dates = fixture.repository.findByDateCalls.toList()
                val latest = fixture.repository.findLatestCalls.toList()

                val returned = fixture.press(bot, confirmation, CANCEL_DELETE)

                assertEquals(card.text, returned.text)
                fixture.assertCard(returned, mode, fixture.trainings, 1)
                assertEquals(card.messageId, returned.messageId)
                assertEquals(dates, fixture.repository.findByDateCalls)
                assertEquals(latest, fixture.repository.findLatestCalls)
                assertTrue(fixture.repository.deleteCalls.isEmpty())
                fixture.assertStaleConfirmation(bot, oldYes)
                val reopened = fixture.openConfirmation(bot, returned, selected)
                fixture.assertStaleConfirmation(bot, oldYes)
                assertEquals(reopened, bot.currentMessage(reopened.messageId))
                if (mode == DeleteViewMode.LATEST) {
                    fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                }

                fixture.press(bot, reopened, CONFIRM_DELETE)

                fixture.assertDeleted(selected)
                fixture.assertStaleConfirmation(bot, oldYes)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.base.assertNoCreation()
            }
        }

    @ParameterizedTest
    @EnumSource(NonCard::class)
    fun `deletion is absent from questions empty results and read errors`(screen: NonCard) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val current = openNonCard(bot, screen)
                val buttons =
                    when (screen) {
                        NonCard.QUESTION -> setOf("Завершить просмотр")
                        NonCard.DATE_EMPTY -> setOf("Выбрать другую дату", "Завершить просмотр")
                        NonCard.LATEST_EMPTY -> emptySet()
                        NonCard.DATE_ERROR, NonCard.LATEST_ERROR -> setOf("Повторить попытку", "Завершить просмотр")
                    }
                fixture.base.assertButtons(current, buttons)
                assertFalse(current.inlineKeyboard.flatten().any { it.text == DELETE_TRAINING })
                assertTrue(fixture.repository.deleteCalls.isEmpty())
                fixture.base.assertNoCreation()
            }
        }

    @ParameterizedTest
    @EnumSource(ConfirmationCommand::class)
    fun `commands retire a pending confirmation without deleting a saved training`(action: ConfirmationCommand) =
        runBlocking<Unit> {
            fixture.base.bot().use { bot ->
                val mode = if (action == ConfirmationCommand.SWITCH_DATE) DeleteViewMode.LATEST else DeleteViewMode.DATE
                val card = fixture.openCard(bot, mode)
                val confirmation = fixture.openConfirmation(bot, card, fixture.trainings[1])
                val oldYes = fixture.capture(bot, confirmation, CONFIRM_DELETE)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())
                val replies = bot.sendCommandAndCollectReplies(action.command, fixture.user.value)

                assertCommandResult(bot, card, action, replies.map { it.text })
                fixture.assertStaleConfirmation(bot, oldYes)
                assertTrue(fixture.repository.deleteCalls.isEmpty())
                assertTrue(fixture.repository.saveCalls.isEmpty())
                if (action == ConfirmationCommand.ADD) {
                    assertEquals(WaitingDate, assertNotNull(fixture.base.storage.get(fixture.user)).state)
                } else {
                    fixture.base.assertNoCreation()
                }
            }
        }

    @Test
    fun `saved training confirmation and deletion preserve an unfinished creation`() =
        runBlocking<Unit> {
            val selected = fixture.trainings[1]
            fixture.allowDelete(selected)
            fixture.base.bot().use { bot ->
                bot.sendCommand("/training_add", fixture.user.value)
                bot.sendTextAndCollectReplies("12.10.2026", fixture.user.value)
                bot.sendTextAndCollectReplies("7:45", fixture.user.value)
                val session = assertNotNull(fixture.base.storage.get(fixture.user))
                val registration = assertNotNull(fixture.base.messages.get(fixture.user))
                val question = bot.currentMessage(registration.messageId)
                val token = fixture.base.messages.questionToken(fixture.user)
                val events = fixture.base.events.toList()
                val card = fixture.openCard(bot, DeleteViewMode.LATEST)
                val confirmation = fixture.openConfirmation(bot, card, selected)
                assertEquals(session, fixture.base.storage.get(fixture.user))
                assertEquals(question, bot.currentMessage(registration.messageId))
                assertEquals(token, fixture.base.messages.questionToken(fixture.user))
                assertEquals(events, fixture.base.events)
                val restored = fixture.press(bot, confirmation, CANCEL_DELETE)
                assertEquals(question, bot.currentMessage(registration.messageId))
                assertEquals(session, fixture.base.storage.get(fixture.user))
                assertEquals(token, fixture.base.messages.questionToken(fixture.user))
                fixture.openConfirmation(bot, restored, selected)
                fixture.repository.findLatestResults[fixture.user] = Result.success(fixture.trainings.last())

                fixture.press(bot, bot.currentMessage(card.messageId), CONFIRM_DELETE)

                fixture.assertDeleted(selected)
                assertEquals(session, fixture.base.storage.get(fixture.user))
                assertEquals(registration, fixture.base.messages.get(fixture.user))
                assertEquals(token, fixture.base.messages.questionToken(fixture.user))
                assertEquals(question, bot.currentMessage(registration.messageId))
                assertEquals(events, fixture.base.events)
                assertTrue(fixture.repository.saveCalls.isEmpty())
            }
        }

    private suspend fun openNonCard(
        bot: TestTelegramBot,
        screen: NonCard,
    ): EditedTelegramMessage {
        val failure = IllegalStateException("Read unavailable")
        return if (screen == NonCard.LATEST_EMPTY || screen == NonCard.LATEST_ERROR) {
            fixture.repository.findLatestResults[fixture.user] =
                if (screen == NonCard.LATEST_EMPTY) Result.success(null) else Result.failure(failure)
            val replies = bot.sendCommandAndCollectReplies("/training_get_last", fixture.user.value)
            assertEquals(1, replies.size)
            bot.currentMessage(TelegramMessageId(replies.single().messageId))
        } else {
            val question = fixture.base.openQuestion(bot)
            if (screen == NonCard.QUESTION) {
                question
            } else {
                fixture.repository.findByDateResults[FindByDateCall(fixture.user, SEARCH_DATE)] =
                    if (screen == NonCard.DATE_EMPTY) Result.success(emptyList()) else Result.failure(failure)
                fixture.base.enterDate(bot, question)
            }
        }
    }

    private fun assertCommandResult(
        bot: TestTelegramBot,
        card: EditedTelegramMessage,
        action: ConfirmationCommand,
        replies: List<String>,
    ) {
        when (action) {
            ConfirmationCommand.CANCEL -> {
                assertTrue(replies.isEmpty())
                assertTrue(bot.deleteMessageAttempts.any { it.messageId == card.messageId && it.succeeded })
                assertNull(fixture.base.activeView())
            }

            ConfirmationCommand.CANCEL_ALL -> {
                assertEquals(
                    listOf(
                        "Все незавершённые действия отменены.\n\n" +
                            "Чтобы записать тренировку, используйте /training_add.",
                    ),
                    replies,
                )
                assertNull(fixture.base.activeView())
                assertTrue(bot.deleteMessageAttempts.any { it.messageId == card.messageId && it.succeeded })
            }

            ConfirmationCommand.ADD -> {
                assertEquals(1, replies.size)
                assertNull(fixture.base.activeView())
                assertTrue(bot.deleteMessageAttempts.any { it.messageId == card.messageId && it.succeeded })
            }

            ConfirmationCommand.NEW_SEARCH, ConfirmationCommand.SWITCH_DATE -> {
                assertTrue(replies.isEmpty())
                fixture.base.assertQuestion(bot.currentMessage(card.messageId))
            }

            ConfirmationCommand.SWITCH_LATEST -> {
                assertTrue(replies.isEmpty())
                fixture.assertCard(bot.currentMessage(card.messageId), DeleteViewMode.LATEST, fixture.trainings, 2)
            }
        }
    }

    internal enum class NonCard { QUESTION, DATE_EMPTY, DATE_ERROR, LATEST_EMPTY, LATEST_ERROR }

    internal enum class ConfirmationCommand(
        val command: String,
    ) {
        CANCEL("/cancel"),
        CANCEL_ALL("/cancel_all"),
        ADD("/training_add"),
        NEW_SEARCH("/training_get_date"),
        SWITCH_LATEST("/training_get_last"),
        SWITCH_DATE("/training_get_date"),
    }
}
