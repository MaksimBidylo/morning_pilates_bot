package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.CONFIRM_DELETE
import morningpilates.telegram.support.DELETE_TRAINING
import morningpilates.telegram.support.DeleteViewMode
import morningpilates.telegram.support.TrainingViewDeleteTestFixture
import morningpilates.telegram.support.assertIgnoredViewCallback
import morningpilates.telegram.support.deliverViewCallbackOnce
import morningpilates.training.FakeTrainingRepository.DeleteCall
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingViewDeleteSecurityTest {
    private val fixture = TrainingViewDeleteTestFixture()
    private val otherUser = TelegramUserId(84L)

    @ParameterizedTest
    @EnumSource(Forgery::class)
    fun `forged confirmation preserves both views and owner can still delete`(kind: Forgery) =
        runBlocking<Unit> {
            val selected = fixture.trainings[1]
            fixture.allowDelete(selected)
            fixture.repository.findLatestResults[otherUser] = Result.success(fixture.trainings.last())
            fixture.base.bot().use { bot ->
                fixture.base.bot(chatId = 8_400L, firstBotMessageId = 2_000L).use { other ->
                    val card = fixture.openCard(bot, DeleteViewMode.DATE)
                    val sent = other.sendCommandAndCollectReplies("/training_get_last", otherUser.value)
                    val otherCard = other.currentMessage(TelegramMessageId(sent.single().messageId))
                    val otherDelete = other.captureCallback(otherCard, DELETE_TRAINING, otherUser)
                    assertTrue(other.deliverViewCallbackOnce(otherDelete).replies.isEmpty())
                    val otherConfirmation = other.currentMessage(otherCard.messageId)
                    fixture.base.assertButtons(otherConfirmation, setOf(CONFIRM_DELETE, "Отмена"))
                    val active =
                        if (kind == Forgery.UNAUTHORIZED_ACTION) card else fixture.openConfirmation(bot, card, selected)
                    val callback =
                        if (kind == Forgery.UNAUTHORIZED_ACTION) {
                            val delete = fixture.capture(bot, card, DELETE_TRAINING)
                            delete.copy(data = delete.data.replace(":delete:", ":delete-yes:"))
                        } else {
                            fixture.capture(bot, active, CONFIRM_DELETE)
                        }
                    val forged = forge(callback, kind)
                    val beforeA = assertNotNull(fixture.base.activeView(fixture.user))
                    val beforeB = assertNotNull(fixture.base.activeView(otherUser))
                    val otherApi = other.apiCalls.toList()
                    val deletes = fixture.repository.deleteCalls.toList()
                    val latest = fixture.repository.findLatestCalls.toList()
                    val dates = fixture.repository.findByDateCalls.toList()

                    fixture.base.assertIgnoredViewCallback(bot, forged)

                    assertEquals(deletes, fixture.repository.deleteCalls)
                    assertEquals(beforeA, fixture.base.activeView(fixture.user))
                    assertEquals(beforeB, fixture.base.activeView(otherUser))
                    assertEquals(active, bot.currentMessage(active.messageId))
                    assertEquals(otherConfirmation, other.currentMessage(otherConfirmation.messageId))
                    assertEquals(otherApi, other.apiCalls)
                    assertEquals(latest, fixture.repository.findLatestCalls)
                    assertEquals(dates, fixture.repository.findByDateCalls)
                    val valid =
                        if (kind == Forgery.UNAUTHORIZED_ACTION) {
                            fixture.openConfirmation(bot, card, selected)
                        } else {
                            active
                        }

                    fixture.press(bot, valid, CONFIRM_DELETE)

                    assertEquals(listOf(DeleteCall(fixture.user, selected.id)), fixture.repository.deleteCalls)
                    assertEquals(beforeB, fixture.base.activeView(otherUser))
                    assertEquals(otherConfirmation, other.currentMessage(otherConfirmation.messageId))
                    assertEquals(otherApi, other.apiCalls)
                    assertEquals(latest, fixture.repository.findLatestCalls)
                    assertEquals(dates, fixture.repository.findByDateCalls)
                    assertTrue(fixture.repository.saveCalls.isEmpty())
                    assertNull(fixture.base.storage.get(otherUser))
                    assertNull(fixture.base.messages.get(otherUser))
                    fixture.base.assertNoCreation()
                }
            }
        }

    @Test
    fun `previous launch confirmation only gets stale notice before and after opening new view`() =
        runBlocking<Unit> {
            val previous =
                fixture.base.bot().use { bot ->
                    val card = fixture.openCard(bot, DeleteViewMode.DATE)
                    val confirmation = fixture.openConfirmation(bot, card, fixture.trainings[1])
                    confirmation to fixture.capture(bot, confirmation, CONFIRM_DELETE)
                }
            val restarted = TrainingViewDeleteTestFixture()
            restarted.base.bot(firstBotMessageId = 3_000L, existingMessages = listOf(previous.first)).use { bot ->
                val notice = "Этот диалог больше неактуален. Используйте меню команд."

                restarted.base.assertIgnoredViewCallback(bot, previous.second, notice)

                assertTrue(restarted.repository.deleteCalls.isEmpty())
                assertTrue(restarted.repository.findLatestCalls.isEmpty())
                assertTrue(restarted.repository.findByDateCalls.isEmpty())
                assertNull(restarted.base.activeView())
                val current = restarted.openCard(bot, DeleteViewMode.LATEST)
                val view = assertNotNull(restarted.base.activeView())
                val reads = restarted.repository.findLatestCalls.toList()

                restarted.base.assertIgnoredViewCallback(bot, previous.second, notice)

                assertEquals(view, restarted.base.activeView())
                assertEquals(current, bot.currentMessage(current.messageId))
                assertEquals(reads, restarted.repository.findLatestCalls)
                assertTrue(restarted.repository.deleteCalls.isEmpty())
                assertEquals(previous.first, bot.currentMessage(previous.first.messageId))
                restarted.base.assertNoCreation()
            }
        }

    private fun forge(
        callback: CallbackQuery,
        kind: Forgery,
    ): CallbackQuery {
        val message = assertNotNull(callback.message)
        return when (kind) {
            Forgery.USER -> callback.copy(from = callback.from.copy(id = otherUser.value))
            Forgery.CHAT -> callback.copy(message = message.copy(chat = message.chat.copy(id = 8_400L)))
            Forgery.MESSAGE -> callback.copy(message = message.copy(messageId = message.messageId + 1))
            Forgery.TOKEN -> callback.copy(data = callback.data + "invalid")
            Forgery.UNAUTHORIZED_ACTION -> callback
        }
    }

    internal enum class Forgery { USER, CHAT, MESSAGE, TOKEN, UNAUTHORIZED_ACTION }
}
