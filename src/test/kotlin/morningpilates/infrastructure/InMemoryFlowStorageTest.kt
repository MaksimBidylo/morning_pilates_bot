package morningpilates.infrastructure

import morningpilates.flow.UserSession
import morningpilates.training.TrainingFlowState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InMemoryFlowStorageTest {
    @Test
    fun `start stores a new user session and returns true`() {
        val storage = InMemoryFlowStorage()
        val userId = 1L
        val session = UserSession(
            state = TrainingFlowState.WaitingDate,
            createdAt = Instant.parse("2026-10-09T09:00:00Z"),
        )

        val started = storage.start(userId, session)

        assertTrue(started)
        val savedSession = assertNotNull(storage.get(userId))
        assertEquals(session, savedSession)
        assertEquals(TrainingFlowState.WaitingDate, savedSession.state)
    }
}
