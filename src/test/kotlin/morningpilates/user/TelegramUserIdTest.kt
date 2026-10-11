package morningpilates.user

import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramUserIdTest {
    @Test
    fun `preserves the original numeric value`() {
        val id = TelegramUserId(7_000_000_123L)

        assertEquals(7_000_000_123L, id.value)
    }

    @Test
    fun `toString redacts the telegram user id`() {
        val id = TelegramUserId(7_000_000_123L)

        assertEquals("TelegramUserId([REDACTED])", id.toString())
    }
}
