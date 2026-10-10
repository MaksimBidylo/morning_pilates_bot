package morningpilates.flow

import morningpilates.flow.TestFlowState.ALTERNATIVE
import morningpilates.flow.TestFlowState.INITIAL
import morningpilates.flow.TestFlowState.NEXT
import morningpilates.infrastructure.InMemoryFlowStorage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlowSessionManagerTest {
    companion object {
        private const val USER_ID = 1L

        @JvmStatic
        private fun transitionStates(): List<FlowState> = listOf(NEXT, Saving("данные тренировки"))

        @JvmStatic
        private fun terminalResults(): List<FlowResult> = listOf(FlowResult.Completed, FlowResult.Cancelled)
    }

    private data class Saving(val details: String) : AwaitingContinuation

    private data class WaitingConfirmation(val details: String) : FlowState

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

    @Test
    fun `missing handler rejects input without handling it or changing the session`() {
        // Given
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        var removeCalls = 0
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls++
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls++
                return backingStorage.remove(userId, expected)
            }
        }
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy()
        val input = FlowEvent.UserInput("текст")
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handlers = List(2) {
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean = false

                override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                    handleCalls += state to event
                    return FlowResult.Transition(NEXT)
                }
            }
        }
        val manager = FlowSessionManager(storage, handlers)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, "Не найден подходящий обработчик")
        assertTrue(handleCalls.isEmpty())
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, updateCalls)
        assertEquals(0, removeCalls)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `conflicting handlers fail without handling input or changing the session regardless of order`(
        reverseOrder: Boolean,
    ) {
        // Given
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        var removeCalls = 0
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls++
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls++
                return backingStorage.remove(userId, expected)
            }
        }
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy()
        val input = FlowEvent.UserInput("текст")
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handlers = listOf(NEXT, ALTERNATIVE).map { nextState ->
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean = state == INITIAL

                override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                    handleCalls += state to event
                    return FlowResult.Transition(nextState)
                }
            }
        }
        val orderedHandlers = if (reverseOrder) handlers.reversed() else handlers
        val manager = FlowSessionManager(storage, orderedHandlers)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val exception = assertFailsWith<IllegalStateException> {
            manager.handle(USER_ID, input)
        }

        // Then
        assertContains(assertNotNull(exception.message), "Несколько обработчиков поддерживают текущее состояние")
        assertTrue(handleCalls.isEmpty())
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, updateCalls)
        assertEquals(0, removeCalls)
    }

    @Test
    fun `missing session rejects input without calling handlers or changing storage`() {
        // Given
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        var removeCalls = 0
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls++
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls++
                return backingStorage.remove(userId, expected)
            }
        }
        val supportsCalls = mutableListOf<FlowState>()
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean {
                supportsCalls += state
                return true
            }

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                handleCalls += state to event
                return FlowResult.Transition(NEXT)
            }
        }
        val manager = FlowSessionManager(storage, listOf(handler))
        val input = FlowEvent.UserInput("текст")

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, "Нет активной сессии")
        assertTrue(supportsCalls.isEmpty())
        assertTrue(handleCalls.isEmpty())
        assertNull(storage.get(USER_ID))
        assertEquals(0, updateCalls)
        assertEquals(0, removeCalls)
    }

    @Test
    fun `handler rejection is returned unchanged without updating or removing the session`() {
        // Given
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        var removeCalls = 0
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls++
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls++
                return backingStorage.remove(userId, expected)
            }
        }
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy()
        val input = FlowEvent.UserInput("текст")
        val expectedResult = FlowResult.Rejected("Недопустимый ввод")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == INITIAL

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                handleCalls += state to event
                return expectedResult
            }
        }
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertSame(expectedResult, result)
        assertEquals(expectedCalls, handleCalls)
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, updateCalls)
        assertEquals(0, removeCalls)
    }

    @ParameterizedTest
    @MethodSource("transitionStates")
    fun `failed conditional update rejects input without continuing or removing the session`(nextState: FlowState) {
        // Given
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val updatedSession = originalSession.copy(state = nextState)
        val concurrentSession = originalSession.copy(state = ALTERNATIVE)
        val expectedSession = concurrentSession.copy()
        val expectedUpdates = listOf(Triple(USER_ID, originalSession, updatedSession))
        val updateCalls = mutableListOf<Triple<Long, UserSession, UserSession>>()
        var removeCalls = 0
        val backingStorage = InMemoryFlowStorage()
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls += Triple(userId, expected, updated)
                backingStorage.update(userId, expected, concurrentSession)
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls++
                return backingStorage.remove(userId, expected)
            }
        }
        val input = FlowEvent.UserInput("текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val continuationCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == INITIAL

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                handleCalls += state to event
                return FlowResult.Transition(nextState)
            }
        }
        val continuationHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == nextState

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                continuationCalls += state to event
                return FlowResult.Completed
            }
        }
        val manager = FlowSessionManager(storage, listOf(handler, continuationHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, "Состояние сессии изменилось")
        assertEquals(expectedCalls, handleCalls)
        assertTrue(continuationCalls.isEmpty())
        assertEquals(expectedUpdates, updateCalls)
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, removeCalls)
    }

    @ParameterizedTest
    @MethodSource("terminalResults")
    fun `terminal result conditionally removes the current session without an intermediate update`(
        terminalResult: FlowResult,
    ) {
        // Given
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        val removeCalls = mutableListOf<Pair<Long, UserSession>>()
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls++
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls += userId to expected
                return backingStorage.remove(userId, expected)
            }
        }
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedRemovals = listOf(USER_ID to originalSession)
        val input = FlowEvent.UserInput("текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == INITIAL

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                handleCalls += state to event
                return terminalResult
            }
        }
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertSame(terminalResult, result)
        assertEquals(expectedCalls, handleCalls)
        assertEquals(0, updateCalls)
        assertEquals(expectedRemovals, removeCalls)
        assertNull(storage.get(USER_ID))
    }

    @Test
    fun `saving transition updates before routing continuation to another handler and completing the flow`() {
        // Given
        val details = "данные тренировки"
        val confirmation = WaitingConfirmation(details)
        val saving = Saving(details)
        val originalSession = UserSession(state = confirmation, createdAt = Instant.EPOCH)
        val savingSession = originalSession.copy(state = saving)
        val expectedSessionsDuringContinuation: List<UserSession?> = listOf(savingSession)
        val expectedUpdates = listOf(Triple(USER_ID, originalSession, savingSession))
        val expectedRemovals = listOf(USER_ID to savingSession)
        val calls = mutableListOf<String>()
        val updateCalls = mutableListOf<Triple<Long, UserSession, UserSession>>()
        val removeCalls = mutableListOf<Pair<Long, UserSession>>()
        val backingStorage = InMemoryFlowStorage()
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                calls += "update"
                updateCalls += Triple(userId, expected, updated)
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                calls += "remove"
                removeCalls += userId to expected
                return backingStorage.remove(userId, expected)
            }
        }
        val input = FlowEvent.UserInput("текст")
        val expectedInputCalls: List<Pair<FlowState, FlowEvent>> = listOf(confirmation to input)
        val expectedContinuationCalls: List<Pair<FlowState, FlowEvent>> = listOf(saving to FlowEvent.Continue)
        val inputCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val continuationCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val sessionsDuringContinuation = mutableListOf<UserSession?>()
        val inputHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state is WaitingConfirmation

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                calls += "input"
                inputCalls += state to event
                return FlowResult.Transition(saving)
            }
        }
        val continuationHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state is Saving

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                calls += "continue"
                continuationCalls += state to event
                sessionsDuringContinuation += storage.get(USER_ID)
                return FlowResult.Completed
            }
        }
        val manager = FlowSessionManager(storage, listOf(continuationHandler, inputHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertSame(FlowResult.Completed, result)
        assertEquals(listOf("input", "update", "continue", "remove"), calls)
        assertEquals(expectedInputCalls, inputCalls)
        assertEquals(expectedContinuationCalls, continuationCalls)
        assertEquals(expectedSessionsDuringContinuation, sessionsDuringContinuation)
        assertEquals(expectedUpdates, updateCalls)
        assertEquals(expectedRemovals, removeCalls)
        assertNull(storage.get(USER_ID))
        assertEquals(details, confirmation.details)
        assertEquals(details, saving.details)
    }

    @Test
    fun `continuation transition returns to confirmation and conditionally updates the saving session`() {
        // Given
        val details = "данные тренировки"
        val confirmation = WaitingConfirmation(details)
        val saving = Saving(details)
        val originalSession = UserSession(state = confirmation, createdAt = Instant.EPOCH)
        val savingSession = originalSession.copy(state = saving)
        val expectedSessionsDuringContinuation: List<UserSession?> = listOf(savingSession)
        val expectedResult = FlowResult.Transition(WaitingConfirmation(details))
        val restoredSession = originalSession.copy(state = expectedResult.nextState)
        val expectedUpdates = listOf(
            Triple(USER_ID, originalSession, savingSession),
            Triple(USER_ID, savingSession, restoredSession),
        )
        val calls = mutableListOf<String>()
        val updateCalls = mutableListOf<Triple<Long, UserSession, UserSession>>()
        var removeCalls = 0
        val backingStorage = InMemoryFlowStorage()
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                calls += "update"
                updateCalls += Triple(userId, expected, updated)
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                calls += "remove"
                removeCalls++
                return backingStorage.remove(userId, expected)
            }
        }
        val input = FlowEvent.UserInput("текст")
        val expectedInputCalls: List<Pair<FlowState, FlowEvent>> = listOf(confirmation to input)
        val expectedContinuationCalls: List<Pair<FlowState, FlowEvent>> = listOf(saving to FlowEvent.Continue)
        val inputCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val continuationCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val sessionsDuringContinuation = mutableListOf<UserSession?>()
        val inputHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state is WaitingConfirmation

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                calls += "input"
                inputCalls += state to event
                return FlowResult.Transition(saving)
            }
        }
        val continuationHandler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state is Saving

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                calls += "continue"
                continuationCalls += state to event
                sessionsDuringContinuation += storage.get(USER_ID)
                return FlowResult.Transition(WaitingConfirmation(assertIs<Saving>(state).details))
            }
        }
        val manager = FlowSessionManager(storage, listOf(continuationHandler, inputHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        assertEquals(expectedResult, result)
        assertEquals(listOf("input", "update", "continue", "update"), calls)
        assertEquals(expectedInputCalls, inputCalls)
        assertEquals(expectedContinuationCalls, continuationCalls)
        assertEquals(expectedSessionsDuringContinuation, sessionsDuringContinuation)
        assertEquals(expectedUpdates, updateCalls)
        assertEquals(restoredSession, storage.get(USER_ID))
        assertEquals(0, removeCalls)
        assertEquals(details, confirmation.details)
        assertEquals(details, saving.details)
    }

    @ParameterizedTest
    @MethodSource("terminalResults")
    fun `failed conditional removal rejects terminal result without changing the current session or retrying`(
        terminalResult: FlowResult,
    ) {
        // Given
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val concurrentSession = originalSession.copy(state = ALTERNATIVE)
        val expectedSession = concurrentSession.copy()
        val expectedRemovals = listOf(USER_ID to originalSession)
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        val removeCalls = mutableListOf<Pair<Long, UserSession>>()
        val storage = object : FlowStorage by backingStorage {
            override fun update(userId: Long, expected: UserSession, updated: UserSession): Boolean {
                updateCalls++
                return backingStorage.update(userId, expected, updated)
            }

            override fun remove(userId: Long, expected: UserSession): Boolean {
                removeCalls += userId to expected
                backingStorage.update(userId, expected, concurrentSession)
                return backingStorage.remove(userId, expected)
            }
        }
        val input = FlowEvent.UserInput("текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler = object : FlowHandler {
            override fun supports(state: FlowState): Boolean = state == INITIAL

            override fun handle(state: FlowState, event: FlowEvent): FlowResult {
                handleCalls += state to event
                return terminalResult
            }
        }
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals("Состояние сессии изменилось", rejected.reason)
        assertEquals(expectedCalls, handleCalls)
        assertEquals(0, updateCalls)
        assertEquals(expectedRemovals, removeCalls)
        assertEquals(expectedSession, storage.get(USER_ID))
    }
}
