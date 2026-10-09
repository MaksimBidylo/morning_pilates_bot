package morningpilates.infrastructure

import morningpilates.flow.FlowState
import morningpilates.flow.TestFlowState.INITIAL
import morningpilates.flow.TestFlowState.NEXT
import morningpilates.flow.UserSession
import org.junit.jupiter.api.Nested
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryFlowStorageTest {

    companion object {
        private const val USER_ID = 1L
        private const val OTHER_USER_ID = 2L
    }

    private fun createSession(
        state: FlowState,
        createdAt: Instant = Instant.EPOCH,
    ) = UserSession(state = state, createdAt = createdAt)

    @Nested
    inner class Start {
        @Test
        fun `stores a new user session and returns true`() {
            // Given
            val storage = InMemoryFlowStorage()
            val session = createSession(INITIAL)

            // When
            val started = storage.start(USER_ID, session)

            // Then
            assertTrue(started)
            assertEquals(session, storage.get(USER_ID))
        }

        @Test
        fun `rejects a second session and preserves the original session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val originalSession = createSession(INITIAL)
            val proposedSession = createSession(
                state = NEXT,
                createdAt = Instant.EPOCH.plusSeconds(60),
            )

            // Populate storage
            assertTrue(storage.start(USER_ID, originalSession))

            // When
            val started = storage.start(USER_ID, proposedSession)

            // Then
            assertFalse(started)
            assertEquals(originalSession, storage.get(USER_ID))
        }

        @Test
        fun `stores sessions independently for different users`() {
            // Given
            val storage = InMemoryFlowStorage()
            val firstSession = createSession(INITIAL)
            val secondSession = createSession(NEXT)

            // When
            val firstStarted = storage.start(USER_ID, firstSession)
            val secondStarted = storage.start(OTHER_USER_ID, secondSession)

            // Then
            assertTrue(firstStarted)
            assertTrue(secondStarted)
            assertEquals(firstSession, storage.get(USER_ID))
            assertEquals(secondSession, storage.get(OTHER_USER_ID))
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `replaces the session when the expected session matches`() {
            // Given
            val storage = InMemoryFlowStorage()
            val originalSession = createSession(INITIAL)
            val updatedSession = createSession(NEXT)

            // Populate storage
            assertTrue(storage.start(USER_ID, originalSession))

            // When
            val updated = storage.update(USER_ID, originalSession, updatedSession)

            // Then
            assertTrue(updated)
            assertEquals(updatedSession, storage.get(USER_ID))
        }

        @Test
        fun `rejects a stale expected session and preserves the current session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val originalSession = createSession(INITIAL)
            val currentSession = createSession(NEXT)
            val updatedSession = originalSession.copy(createdAt = Instant.EPOCH.plusSeconds(60))

            // Populate storage
            assertTrue(storage.start(USER_ID, originalSession))
            assertTrue(storage.update(USER_ID, originalSession, currentSession))

            // When
            val updated = storage.update(USER_ID, originalSession, updatedSession)

            // Then
            assertFalse(updated)
            assertEquals(currentSession, storage.get(USER_ID))
        }

        @Test
        fun `returns false and does not create a missing session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val originalSession = createSession(INITIAL)
            val updatedSession = createSession(NEXT)

            // When
            val updated = storage.update(USER_ID, originalSession, updatedSession)

            // Then
            assertFalse(updated)
            assertNull(storage.get(USER_ID))
        }
    }

    @Nested
    inner class Remove {
        @Test
        fun `deletes the session when the expected session matches`() {
            // Given
            val storage = InMemoryFlowStorage()
            val currentSession = createSession(INITIAL)

            // Populate storage
            assertTrue(storage.start(USER_ID, currentSession))

            // When
            val removed = storage.remove(USER_ID, currentSession)

            // Then
            assertTrue(removed)
            assertNull(storage.get(USER_ID))
        }

        @Test
        fun `rejects a stale expected session and preserves the current session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val originalSession = createSession(INITIAL)
            val currentSession = createSession(NEXT)

            // Populate storage
            assertTrue(storage.start(USER_ID, originalSession))
            assertTrue(storage.update(USER_ID, originalSession, currentSession))

            // When
            val removed = storage.remove(USER_ID, originalSession)

            // Then
            assertFalse(removed)
            assertEquals(currentSession, storage.get(USER_ID))
        }

        @Test
        fun `returns false for a missing session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val expectedSession = createSession(INITIAL)

            // When
            val removed = storage.remove(USER_ID, expectedSession)

            // Then
            assertFalse(removed)
            assertNull(storage.get(USER_ID))
        }
    }
}
