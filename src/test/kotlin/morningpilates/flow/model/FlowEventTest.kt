package morningpilates.flow.model

import morningpilates.user.TelegramUserId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class FlowEventTest {
    @Test
    fun `user input toString redacts the telegram user id`() {
        val telegramUserId = TelegramUserId(7_000_000_123L)
        val event = FlowEvent.UserInput(telegramUserId, "текст")

        val description = event.toString()

        assertContains(description, "TelegramUserId([REDACTED])")
        assertFalse(description.contains(telegramUserId.value.toString()))
    }

    @Test
    fun `continuation toString redacts the telegram user id`() {
        val telegramUserId = TelegramUserId(7_000_000_123L)
        val event = FlowEvent.Continue(telegramUserId, SessionId.new())

        val description = event.toString()

        assertContains(description, "TelegramUserId([REDACTED])")
        assertFalse(description.contains(telegramUserId.value.toString()))
    }
}
