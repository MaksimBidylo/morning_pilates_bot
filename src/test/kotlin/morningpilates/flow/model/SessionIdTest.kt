package morningpilates.flow.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SessionIdTest {
    @Test
    fun `preserves an explicitly supplied UUID`() {
        val value = UUID.randomUUID()

        val sessionId = SessionId(value)

        assertEquals(value, sessionId.value)
    }

    @Test
    fun `new creates independent session identifiers`() {
        val first = SessionId.new()
        val second = SessionId.new()

        assertNotEquals(first.value, second.value)
    }
}
