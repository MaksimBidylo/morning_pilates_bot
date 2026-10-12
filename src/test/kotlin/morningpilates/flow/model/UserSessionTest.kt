package morningpilates.flow.model

import morningpilates.flow.model.TestFlowState.INITIAL
import morningpilates.flow.model.TestFlowState.NEXT
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UserSessionTest {
    @Test
    fun `new sessions with the same state and creation time have independent identifiers`() {
        val first = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val second = UserSession(state = INITIAL, createdAt = Instant.EPOCH)

        assertNotEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun `copy preserves the explicit session identifier during a state transition`() {
        val sessionId = SessionId(UUID.randomUUID())
        val original = UserSession(state = INITIAL, createdAt = Instant.EPOCH, sessionId = sessionId)

        val updated = original.copy(state = NEXT)

        assertEquals(sessionId, original.sessionId)
        assertEquals(sessionId, updated.sessionId)
        assertEquals(NEXT, updated.state)
        assertEquals(original.createdAt, updated.createdAt)
    }
}
