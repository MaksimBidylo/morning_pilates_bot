package morningpilates.infrastructure

import morningpilates.flow.UserSession
import morningpilates.training.TrainingFlowState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryFlowStorageTest {
    @Test
    fun `start stores a new user session and returns true`() {
        // Given
        val storage = InMemoryFlowStorage()
        val userId = 1L
        val session = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH,)

        // When
        val started = storage.start(userId, session)

        // Then
        assertTrue(started)
        assertEquals(session, storage.get(userId))
    }

    @Test
    fun `start rejects a second session and preserves the original session`() {
        // Given
        val storage = InMemoryFlowStorage()
        val userId = 1L
        val originalSession = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH,)
        val newSession = UserSession(
            state = TrainingFlowState.WaitingStartTime,
            createdAt = Instant.EPOCH.plusSeconds(60),
        )
        assertTrue(storage.start(userId, originalSession))

        // When
        val started = storage.start(userId, newSession)

        // Then
        assertFalse(started)
        assertEquals(originalSession, storage.get(userId))
    }
}
