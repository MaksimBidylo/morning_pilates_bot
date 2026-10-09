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
        val session = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH)

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
        val originalSession = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH)
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

    @Test
    fun `start stores sessions independently for different users`() {
        // Given
        val storage = InMemoryFlowStorage()
        val firstUserId = 1L
        val secondUserId = 2L
        val firstSession = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH)
        val secondSession = UserSession(state = TrainingFlowState.WaitingStartTime, createdAt = Instant.EPOCH)

        // When
        val firstStarted = storage.start(firstUserId, firstSession)
        val secondStarted = storage.start(secondUserId, secondSession)

        // Then
        assertTrue(firstStarted)
        assertTrue(secondStarted)
        assertEquals(firstSession, storage.get(firstUserId))
        assertEquals(secondSession, storage.get(secondUserId))
    }

    @Test
    fun `update replaces the session when the expected session matches`() {
        // Given
        val storage = InMemoryFlowStorage()
        val userId = 1L
        val originalSession = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH)
        val updatedSession = UserSession(state = TrainingFlowState.WaitingStartTime, createdAt = Instant.EPOCH)
        assertTrue(storage.start(userId, originalSession))

        // When
        val updated = storage.update(userId, originalSession, updatedSession)

        // Then
        assertTrue(updated)
        assertEquals(updatedSession, storage.get(userId))
    }

    @Test
    fun `update rejects a stale expected session and preserves the current session`() {
        // Given
        val storage = InMemoryFlowStorage()
        val userId = 1L
        val originalSession = UserSession(state = TrainingFlowState.WaitingDate, createdAt = Instant.EPOCH)
        val currentSession = UserSession(state = TrainingFlowState.WaitingStartTime, createdAt = Instant.EPOCH)
        val updatedSession = originalSession.copy(createdAt = Instant.EPOCH.plusSeconds(60))
        assertTrue(storage.start(userId, originalSession))
        assertTrue(storage.update(userId, originalSession, currentSession))

        // When
        val updated = storage.update(userId, originalSession, updatedSession)

        // Then
        assertFalse(updated)
        assertEquals(currentSession, storage.get(userId))
    }
}
