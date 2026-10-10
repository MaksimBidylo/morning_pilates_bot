package morningpilates.flow

import morningpilates.flow.TestFlowState.INITIAL
import morningpilates.flow.TestFlowState.NEXT
import morningpilates.infrastructure.InMemoryFlowStorage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
            override fun supports(state: FlowState): Boolean = state == INITIAL

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                calls += state to event
                return expectedResult
            }
        }
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertEquals(expectedResult, result)
        assertEquals(expectedCalls, calls)
        assertEquals(expectedSession, storage.get(USER_ID))
    }

    @Test
    fun `only the handler supporting the current state handles user input`() {
        // Given
        val storage = InMemoryFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy(state = NEXT)
        val input = FlowEvent.UserInput("текст")
        val expectedResult = FlowResult.Transition(NEXT)
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val unsupportedCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val supportedCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val unsupportedHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == NEXT

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                unsupportedCalls += state to event
                return expectedResult
            }
        }
        val supportedHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == INITIAL

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                supportedCalls += state to event
                return expectedResult
            }
        }
        val manager = FlowSessionManager(storage, listOf(unsupportedHandler, supportedHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertEquals(expectedResult, result)
        assertEquals(expectedCalls, supportedCalls)
        assertSame(input, supportedCalls.single().second)
        assertTrue(unsupportedCalls.isEmpty())
        assertEquals(expectedSession, storage.get(USER_ID))
    }
}
