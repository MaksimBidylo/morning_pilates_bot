package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.DATE_VIEW_PREVIOUS
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.telegram.support.assertIgnoredViewCallback
import morningpilates.telegram.support.deliverViewCallbackOnce
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingDateViewCallbackSecurityTest {
    private val fixture = TrainingDateViewTestFixture()
    private val otherUser = TelegramUserId(84L)
    private val otherTrainings =
        listOf(
            fixture.trainings.first().copy(
                details =
                    fixture.trainings
                        .first()
                        .details
                        .copy(note = "Only user B"),
            ),
        )

    @ParameterizedTest
    @EnumSource(Forgery::class)
    fun `forged callbacks cannot change either users view or read their trainings`(kind: Forgery) =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.repository.findByDateResults[FindByDateCall(otherUser, SEARCH_DATE)] =
                Result.success(otherTrainings)
            fixture.bot().use { bot ->
                fixture.bot(chatId = 8_400L, firstBotMessageId = 2_000L).use { other ->
                    val first = fixture.enterDate(bot, fixture.openQuestion(bot))
                    val otherCard = openOtherView(other)
                    val active = if (kind == Forgery.AFTER_LAST) lastCard(bot, first) else first
                    val button =
                        if (kind == Forgery.AFTER_LAST) DATE_VIEW_PREVIOUS else DATE_VIEW_NEXT
                    val original = bot.captureCallback(active, button, fixture.user)
                    val forged = forge(original, kind)
                    val otherApi = other.apiCalls.toList()
                    val beforeA = assertNotNull(fixture.activeView(fixture.user))
                    val beforeB = assertNotNull(fixture.activeView(otherUser))

                    fixture.assertIgnoredViewCallback(bot, forged)

                    assertEquals(beforeA, fixture.activeView(fixture.user))
                    assertEquals(beforeB, fixture.activeView(otherUser))
                    assertEquals(active, bot.currentMessage(active.messageId))
                    assertEquals(otherCard, other.currentMessage(otherCard.messageId))
                    assertEquals(otherApi, other.apiCalls)
                    assertNull(fixture.storage.get(otherUser))
                    assertNull(fixture.messages.get(otherUser))
                    assertEquals(
                        listOf(FindByDateCall(fixture.user, SEARCH_DATE), FindByDateCall(otherUser, SEARCH_DATE)),
                        fixture.repository.findByDateCalls,
                    )
                    assertTrue(bot.deliverViewCallbackOnce(original).replies.isEmpty())
                    assertEquals(beforeB, fixture.activeView(otherUser))
                    fixture.assertCard(bot.currentMessage(active.messageId), fixture.trainings, 1)
                    assertEquals(otherCard, other.currentMessage(otherCard.messageId))
                    assertEquals(2, fixture.repository.findByDateCalls.size)
                    assertTrue(fixture.repository.findLatestCalls.isEmpty())
                    fixture.assertNoCreation()
                }
            }
        }

    private suspend fun openOtherView(bot: TestTelegramBot): EditedTelegramMessage {
        val sent = bot.sendCommandAndCollectReplies("/training_get_date", otherUser.value)
        assertEquals(1, sent.size)
        val question = bot.currentMessage(TelegramMessageId(sent.single().messageId))
        fixture.assertQuestion(question)
        assertTrue(bot.sendTextAndCollectReplies("12.10.2026", otherUser.value).isEmpty())
        return bot.currentMessage(question.messageId).also { fixture.assertCard(it, otherTrainings, 0) }
    }

    private suspend fun lastCard(
        bot: TestTelegramBot,
        first: EditedTelegramMessage,
    ): EditedTelegramMessage {
        var current = first
        repeat(2) {
            val next = bot.captureCallback(current, DATE_VIEW_NEXT, fixture.user)
            assertTrue(bot.deliverViewCallbackOnce(next).replies.isEmpty())
            current = bot.currentMessage(first.messageId)
        }
        fixture.assertCard(current, fixture.trainings, 2)
        return current
    }

    private fun forge(
        callback: CallbackQuery,
        kind: Forgery,
    ): CallbackQuery {
        val message = assertNotNull(callback.message)
        return when (kind) {
            Forgery.USER -> callback.copy(from = callback.from.copy(id = otherUser.value))
            Forgery.CHAT -> callback.copy(message = message.copy(chat = message.chat.copy(id = 8_400L)))
            Forgery.CHAT_TYPE -> callback.copy(message = message.copy(chat = message.chat.copy(type = "group")))
            Forgery.MESSAGE -> callback.copy(message = message.copy(messageId = message.messageId + 1))
            Forgery.TOKEN -> callback.copy(data = callback.data + "invalid")
            Forgery.RETRY -> callback.copy(data = callback.data.replace(":next:", ":retry:"))
            Forgery.BEFORE_FIRST -> callback.copy(data = callback.data.replace(":next:", ":previous:"))
            Forgery.AFTER_LAST -> callback.copy(data = callback.data.replace(":previous:", ":next:"))
        }
    }

    internal enum class Forgery { USER, CHAT, CHAT_TYPE, MESSAGE, TOKEN, RETRY, BEFORE_FIRST, AFTER_LAST }
}
