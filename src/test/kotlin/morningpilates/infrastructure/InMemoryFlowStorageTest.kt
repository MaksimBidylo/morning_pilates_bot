package morningpilates.infrastructure

import morningpilates.flow.model.FlowState
import morningpilates.flow.model.TestFlowState.ALTERNATIVE
import morningpilates.flow.model.TestFlowState.INITIAL
import morningpilates.flow.model.TestFlowState.NEXT
import morningpilates.flow.model.UserSession
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Nested
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryFlowStorageTest {
    companion object {
        private val USER_ID = TelegramUserId(1L)
        private val OTHER_USER_ID = TelegramUserId(2L)
    }

    private fun createSession(
        state: FlowState,
        createdAt: Instant = Instant.EPOCH,
    ) = UserSession(state = state, createdAt = createdAt)

    private fun runConcurrently(
        sessions: List<UserSession>,
        operation: (UserSession) -> Boolean,
    ): List<Boolean> {
        val ready = CountDownLatch(sessions.size)
        val startSignal = CountDownLatch(1)
        val timeoutSeconds = 5L
        val executor = Executors.newFixedThreadPool(sessions.size)

        try {
            val attempts =
                sessions.map { session ->
                    executor.submit<Boolean> {
                        ready.countDown()
                        assertTrue(
                            startSignal.await(timeoutSeconds, TimeUnit.SECONDS),
                            "Start signal timed out",
                        )
                        operation(session)
                    }
                }
            assertTrue(
                ready.await(timeoutSeconds, TimeUnit.SECONDS),
                "Workers were not ready in time",
            )

            startSignal.countDown()
            return attempts.map { it.get(timeoutSeconds, TimeUnit.SECONDS) }
        } finally {
            startSignal.countDown()
            executor.shutdownNow()
            assertTrue(
                executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS),
                "Workers did not terminate",
            )
        }
    }

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
            val proposedSession =
                createSession(
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

        @Test
        fun `allows only one concurrent session creation for the same user`() {
            // Given
            val storage = InMemoryFlowStorage()
            val candidateSessions = listOf(createSession(INITIAL), createSession(NEXT))

            // When
            val results =
                runConcurrently(candidateSessions) { session ->
                    storage.start(USER_ID, session)
                }

            // Then
            assertEquals(1, results.count { it })
            assertEquals(1, results.count { !it })
            val winningSession = candidateSessions[results.indexOf(true)]
            assertEquals(winningSession, storage.get(USER_ID))
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

        @Test
        fun `allows only one concurrent update for the same expected session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val originalSession = createSession(INITIAL)
            val candidateSessions = listOf(createSession(NEXT), createSession(ALTERNATIVE))

            // Populate storage
            assertTrue(storage.start(USER_ID, originalSession))

            // When
            val results =
                runConcurrently(candidateSessions) { session ->
                    storage.update(USER_ID, originalSession, session)
                }

            // Then
            assertEquals(1, results.count { it })
            assertEquals(1, results.count { !it })
            val winningSession = candidateSessions[results.indexOf(true)]
            assertEquals(winningSession, storage.get(USER_ID))
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

        @Test
        fun `allows only one concurrent removal for the same expected session`() {
            // Given
            val storage = InMemoryFlowStorage()
            val currentSession = createSession(INITIAL)

            // Populate storage
            assertTrue(storage.start(USER_ID, currentSession))

            // When
            val results =
                runConcurrently(listOf(currentSession, currentSession)) { session ->
                    storage.remove(USER_ID, session)
                }

            // Then
            assertEquals(1, results.count { it })
            assertEquals(1, results.count { !it })
            assertNull(storage.get(USER_ID))
        }
    }
}
