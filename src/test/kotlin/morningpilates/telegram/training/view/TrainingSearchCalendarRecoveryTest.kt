package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.INVALID_DATE_REASON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingSearchCalendarRecoveryTest {
    @Test
    fun `explicit recovery sends a visible calendar without editing and rejects every old button`() =
        runBlocking<Unit> {
            val fixture = TrainingDateViewTestFixture(calendarEnabled = true)
            fixture.bot().use { bot ->
                val first = bot.sendCommandAndCollectReplies("/training_get_date").single()
                val old = bot.currentMessage(TelegramMessageId(first.messageId))
                val day = bot.captureCallback(old, "12")
                val finish = bot.captureCallback(old, "Завершить просмотр")
                val before = bot.apiCalls.size
                val replacement = bot.sendCommandAndCollectReplies("/training_get_date").single()
                assertNotEquals(first.messageId, replacement.messageId)
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(before))
                val active = fixture.activeView()
                bot.deliverCallbackAndCollectReplies(day)
                bot.deliverCallbackAndCollectReplies(finish)
                assertEquals(active, fixture.activeView())
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                val date = checkNotNull(active?.dateSearch?.calendar).month.atDay(12)
                fixture.configure(emptyList(), date)
                val current = bot.currentMessage(TelegramMessageId(replacement.messageId))
                bot.pressInlineButtonAndCollectReplies(current, "12")
                assertEquals(listOf(FindByDateCall(fixture.user, date)), fixture.repository.findByDateCalls)
            }
        }

    @Test
    fun `failed recovery retains state and command and another command recovers search`() =
        runBlocking<Unit> {
            val fixture = TrainingDateViewTestFixture(calendarEnabled = true)
            fixture.bot().use { bot ->
                val first = bot.sendCommandAndCollectReplies("/training_get_date").single()
                val id = TelegramMessageId(first.messageId)
                bot.pressInlineButtonAndCollectReplies(bot.currentMessage(id), "›")
                val active = fixture.activeView()
                val old = bot.captureCallback(bot.currentMessage(id), "12")
                val before = bot.apiCalls.size
                bot.failNextSendMessage("Unavailable")
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date", messageId = 9000).isEmpty())
                assertEquals(listOf("sendMessage"), bot.apiCalls.drop(before))
                assertEquals(active, fixture.activeView())
                assertTrue(bot.deleteMessageAttempts.none { it.messageId == TelegramMessageId(9000) })
                val replacement = bot.sendCommandAndCollectReplies("/training_get_date").single()
                assertNotEquals(first.messageId, replacement.messageId)
                assertNotEquals(active?.token, fixture.activeView()?.token)
                bot.deliverCallbackAndCollectReplies(old)
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                val current = bot.currentMessage(TelegramMessageId(replacement.messageId))
                bot.pressInlineButtonAndCollectReplies(current, "›")
                assertEquals(3, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `disabled flag keeps repeat command in existing text message`() =
        runBlocking<Unit> {
            val fixture = TrainingDateViewTestFixture()
            fixture.bot().use { bot ->
                val first = fixture.openQuestion(bot)
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date").isEmpty())
                fixture.assertQuestion(bot.currentMessage(first.messageId))
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.enterDate(bot, first, "31.02.2026")
                val warning = bot.currentMessage(first.messageId)
                assertEquals("⚠️ $INVALID_DATE_REASON\n\n${first.text}", warning.text)
                fixture.configure(emptyList())
                val result = fixture.enterDate(bot, first)
                assertTrue(result.text.contains("тренировок не найдено"))
                assertNull(fixture.activeView()?.dateSearch?.calendar)
            }
        }
}
