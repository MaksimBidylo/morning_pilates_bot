package morningpilates.infrastructure

import morningpilates.flow.UserSession
import morningpilates.training.TrainingFlowState
import org.junit.jupiter.api.Nested
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryFlowStorageTest {
    @Nested
    inner class Start {
        @Test
        fun `start stores a new user session and returns true`() {
            // Given
            val storage = InMemoryFlowStorage()
            val userId = 1L
            val session = session(TrainingFlowState.WaitingDate)

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
            val originalSession = session(TrainingFlowState.WaitingDate)
            val proposedSession = session(
                state = TrainingFlowState.WaitingStartTime,
                createdAt = Instant.EPOCH.plusSeconds(60),
            )
            assertTrue(storage.start(userId, originalSession))

            // When
            val started = storage.start(userId, proposedSession)

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
            val firstSession = session(TrainingFlowState.WaitingDate)
            val secondSession = session(TrainingFlowState.WaitingStartTime)

            // When
            val firstStarted = storage.start(firstUserId, firstSession)
            val secondStarted = storage.start(secondUserId, secondSession)

            // Then
            assertTrue(firstStarted)
            assertTrue(secondStarted)
            assertEquals(firstSession, storage.get(firstUserId))
            assertEquals(secondSession, storage.get(secondUserId))
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `update replaces the session when the expected session matches`() {
            // Given
            val storage = InMemoryFlowStorage()
            val userId = 1L
            val originalSession = session(TrainingFlowState.WaitingDate)
            val updatedSession = session(TrainingFlowState.WaitingStartTime)
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
            val originalSession = session(TrainingFlowState.WaitingDate)
            val currentSession = session(TrainingFlowState.WaitingStartTime)
            val updatedSession = originalSession.copy(createdAt = Instant.EPOCH.plusSeconds(60))
            assertTrue(storage.start(userId, originalSession))
            assertTrue(storage.update(userId, originalSession, currentSession))

            // When
            val updated = storage.update(userId, originalSession, updatedSession)

            // Then
            assertFalse(updated)
            assertEquals(currentSession, storage.get(userId))
        }

        @Test
        fun `update returns false and does not create a missing session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val userId = 1L
            val originalSession = session(TrainingFlowState.WaitingDate)
            val updatedSession = session(TrainingFlowState.WaitingStartTime)

            // When
            val updated = storage.update(userId, originalSession, updatedSession)

            // Then
            assertFalse(updated)
            assertNull(storage.get(userId))
        }
    }

    @Nested
    inner class Remove {
        @Test
        fun `remove deletes the session when the expected session matches`() {
            // Given
            val storage = InMemoryFlowStorage()
            val userId = 1L
            val currentSession = session(TrainingFlowState.WaitingDate)
            assertTrue(storage.start(userId, currentSession))

            // When
            val removed = storage.remove(userId, currentSession)

            // Then
            assertTrue(removed)
            assertNull(storage.get(userId))
        }

        @Test
        fun `remove rejects a stale expected session and preserves the current session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val userId = 1L
            val originalSession = session(TrainingFlowState.WaitingDate)
            val currentSession = session(TrainingFlowState.WaitingStartTime)
            assertTrue(storage.start(userId, originalSession))
            assertTrue(storage.update(userId, originalSession, currentSession))

            // When
            val removed = storage.remove(userId, originalSession)

            // Then
            assertFalse(removed)
            assertEquals(currentSession, storage.get(userId))
        }

        @Test
        fun `remove returns false for a missing session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val userId = 1L
            val expectedSession = session(TrainingFlowState.WaitingDate)

            // When
            val removed = storage.remove(userId, expectedSession)

            // Then
            assertFalse(removed)
            assertNull(storage.get(userId))
        }
    }

    private fun session(
        state: TrainingFlowState,
        createdAt: Instant = Instant.EPOCH,
    ) = UserSession(state = state, createdAt = createdAt)
}
