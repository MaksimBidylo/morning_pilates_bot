package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TrainingDateViewTestFixture
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingSearchCalendarWiringTest {
    @Test
    fun `application registration enables search calendar independently of creation`() =
        runBlocking<Unit> {
            val fixture = TrainingDateViewTestFixture(calendarEnabled = true)
            fixture.bot().use { bot ->
                val question = bot.sendCommandAndCollectReplies("/training_get_date").single()
                assertNotNull(fixture.activeView()?.dateSearch?.calendar)
                assertTrue(question.inlineButtonTexts.contains("Пн"))
                bot.sendCommandAndCollectReplies("/cancel")
                val creation = bot.sendCommandAndCollectReplies("/training_add").single()
                assertTrue(creation.text.contains("Введите дату тренировки"))
                assertTrue(creation.inlineButtonTexts.none { it == "Пн" })
                bot.sendCommandAndCollectReplies("/training_get_date")
                assertNull(fixture.activeView())
                val current = bot.currentMessage(TelegramMessageId(creation.messageId))
                assertTrue(current.text.contains("незавершённая тренировка"))
            }
        }
}
