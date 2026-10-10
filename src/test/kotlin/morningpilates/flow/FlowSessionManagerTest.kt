package morningpilates.flow

import morningpilates.flow.TestFlowState.INITIAL
import morningpilates.flow.TestFlowState.NEXT
import morningpilates.infrastructure.InMemoryFlowStorage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowSessionManagerTest {
    companion object {
        private const val USER_ID = 1L
    }

    @Test
    fun `a transition updates the user session and preserves its creation time`() {
        // Given
        val storage = InMemoryFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy(state = NEXT)
        val input = FlowEvent.UserInput("текст")
        val expectedResult = FlowResult.Transition(NEXT)
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val calls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler = object : FlowHandler {
            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                calls += state to event
                return expectedResult
            }
        }
        val manager = FlowSessionManager(storage, handler)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertEquals(expectedResult, result)
        assertEquals(expectedCalls, calls)
        assertEquals(expectedSession, storage.get(USER_ID))
    }
}
