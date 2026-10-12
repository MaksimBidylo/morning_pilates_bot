package morningpilates.flow.session

import morningpilates.flow.FlowHandler
import morningpilates.flow.model.AwaitingContinuation
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.model.TestFlowState.ALTERNATIVE
import morningpilates.flow.model.TestFlowState.INITIAL
import morningpilates.flow.model.TestFlowState.NEXT
import morningpilates.flow.model.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.user.TelegramUserId
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
        private val USER_ID = TelegramUserId(1L)

        @JvmStatic
        private fun transitionStates(): List<FlowState> = listOf(NEXT, Saving("данные тренировки"))

        @JvmStatic
        private fun terminalResults(): List<FlowResult> = listOf(FlowResult.Completed, FlowResult.Cancelled)
    }

    private data class Saving(
        val details: String,
    ) : AwaitingContinuation

    private data class WaitingConfirmation(
        val details: String,
    ) : FlowState

    @Test
    fun `input selects its user session without changing another user session`() {
        val storage = InMemoryFlowStorage()
        val otherUserId = TelegramUserId(2L)
        val firstSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val otherSession = UserSession(state = ALTERNATIVE, createdAt = Instant.EPOCH.plusSeconds(60))
        val input = FlowEvent.UserInput(otherUserId, "текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(ALTERNATIVE to input)
        val calls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val expectedResult = FlowResult.Transition(NEXT)
        val handler =
            recordingHandler(
                supportsState = { it == ALTERNATIVE },
                result = expectedResult,
                calls = calls,
            )
        val manager = FlowSessionManager(storage, listOf(handler))
        assertTrue(storage.start(USER_ID, firstSession))
        assertTrue(storage.start(otherUserId, otherSession))

        val result = manager.handle(input)

        assertEquals(expectedResult, result)
        assertEquals(expectedCalls, calls)
        assertEquals(firstSession, storage.get(USER_ID))
        assertEquals(otherSession.copy(state = NEXT), storage.get(otherUserId))
    }

    @Test
    fun `a transition updates the user session and preserves its creation time`() {
        // Given
        val storage = InMemoryFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy(state = NEXT)
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedResult = FlowResult.Transition(NEXT)
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val calls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler =
            recordingHandler(
                supportsState = { it == INITIAL },
                result = expectedResult,
                calls = calls,
            )
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

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
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedResult = FlowResult.Transition(NEXT)
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val unsupportedCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val supportedCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val unsupportedHandler =
            recordingHandler(
                supportsState = { it == NEXT },
                result = expectedResult,
                calls = unsupportedCalls,
            )
        val supportedHandler =
            recordingHandler(
                supportsState = { it == INITIAL },
                result = expectedResult,
                calls = supportedCalls,
            )
        val manager = FlowSessionManager(storage, listOf(unsupportedHandler, supportedHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

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
        val storage = RecordingFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy()
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handlers =
            List(2) {
                recordingHandler(
                    supportsState = { false },
                    result = FlowResult.Transition(NEXT),
                    calls = handleCalls,
                )
            }
        val manager = FlowSessionManager(storage, handlers)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, "Не найден подходящий обработчик")
        assertTrue(handleCalls.isEmpty())
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, storage.updateCalls.size)
        assertEquals(0, storage.removeCalls.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `conflicting handlers fail in either order without side effects`(reverseOrder: Boolean) {
        // Given
        val storage = RecordingFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy()
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handlers =
            listOf(NEXT, ALTERNATIVE).map { nextState ->
                recordingHandler(
                    supportsState = { it == INITIAL },
                    result = FlowResult.Transition(nextState),
                    calls = handleCalls,
                )
            }
        val orderedHandlers = if (reverseOrder) handlers.reversed() else handlers
        val manager = FlowSessionManager(storage, orderedHandlers)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val exception =
            assertFailsWith<IllegalStateException> {
                manager.handle(input)
            }

        // Then
        assertContains(assertNotNull(exception.message), "Несколько обработчиков поддерживают текущее состояние")
        assertTrue(handleCalls.isEmpty())
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, storage.updateCalls.size)
        assertEquals(0, storage.removeCalls.size)
    }

    @Test
    fun `missing session rejects input without calling handlers or changing storage`() {
        // Given
        val storage = RecordingFlowStorage()
        val supportsCalls = mutableListOf<FlowState>()
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler =
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean {
                    supportsCalls += state
                    return true
                }

                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ): FlowResult {
                    handleCalls += state to event
                    return FlowResult.Transition(NEXT)
                }
            }
        val manager = FlowSessionManager(storage, listOf(handler))
        val input = FlowEvent.UserInput(USER_ID, "текст")

        // When
        val result = manager.handle(input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, "Нет активной сессии")
        assertTrue(supportsCalls.isEmpty())
        assertTrue(handleCalls.isEmpty())
        assertNull(storage.get(USER_ID))
        assertEquals(0, storage.updateCalls.size)
        assertEquals(0, storage.removeCalls.size)
    }

    @Test
    fun `handler rejection is returned unchanged without updating or removing the session`() {
        // Given
        val storage = RecordingFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy()
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedResult = FlowResult.Rejected("Недопустимый ввод")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler =
            recordingHandler(
                supportsState = { it == INITIAL },
                result = expectedResult,
                calls = handleCalls,
            )
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

        // Then
        assertSame(expectedResult, result)
        assertEquals(expectedCalls, handleCalls)
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(0, storage.updateCalls.size)
        assertEquals(0, storage.removeCalls.size)
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
        val updateCalls = mutableListOf<Triple<TelegramUserId, UserSession, UserSession>>()
        var removeCalls = 0
        val backingStorage = InMemoryFlowStorage()
        val storage =
            object : FlowStorage by backingStorage {
                override fun update(
                    userId: TelegramUserId,
                    expected: UserSession,
                    updated: UserSession,
                ): Boolean {
                    updateCalls += Triple(userId, expected, updated)
                    backingStorage.update(userId, expected, concurrentSession)
                    return backingStorage.update(userId, expected, updated)
                }

                override fun remove(
                    userId: TelegramUserId,
                    expected: UserSession,
                ): Boolean {
                    removeCalls++
                    return backingStorage.remove(userId, expected)
                }
            }
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val continuationCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler =
            recordingHandler(
                supportsState = { it == INITIAL },
                result = FlowResult.Transition(nextState),
                calls = handleCalls,
            )
        val continuationHandler =
            recordingHandler(
                supportsState = { it == nextState },
                result = FlowResult.Completed,
                calls = continuationCalls,
            )
        val manager = FlowSessionManager(storage, listOf(handler, continuationHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

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
    fun `terminal result removes the session without an intermediate update`(terminalResult: FlowResult) {
        // Given
        val storage = RecordingFlowStorage()
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val expectedRemovals = listOf(USER_ID to originalSession)
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler =
            recordingHandler(
                supportsState = { it == INITIAL },
                result = terminalResult,
                calls = handleCalls,
            )
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

        // Then
        assertSame(terminalResult, result)
        assertEquals(expectedCalls, handleCalls)
        assertEquals(0, storage.updateCalls.size)
        assertEquals(expectedRemovals, storage.removeCalls)
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
        val storage = RecordingFlowStorage(trace = calls)
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedInputCalls: List<Pair<FlowState, FlowEvent>> = listOf(confirmation to input)
        val expectedContinuationCalls: List<Pair<FlowState, FlowEvent>> =
            listOf(saving to FlowEvent.Continue(USER_ID, originalSession.sessionId))
        val inputCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val continuationCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val sessionsDuringContinuation = mutableListOf<UserSession?>()
        val inputHandler =
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean = state is WaitingConfirmation

                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ): FlowResult {
                    calls += "input"
                    inputCalls += state to event
                    return FlowResult.Transition(saving)
                }
            }
        val continuationHandler =
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean = state is Saving

                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ): FlowResult {
                    calls += "continue"
                    continuationCalls += state to event
                    sessionsDuringContinuation += storage.get(USER_ID)
                    return FlowResult.Completed
                }
            }
        val manager = FlowSessionManager(storage, listOf(continuationHandler, inputHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

        // Then
        assertSame(FlowResult.Completed, result)
        assertEquals(listOf("input", "update", "continue", "remove"), calls)
        assertEquals(expectedInputCalls, inputCalls)
        assertEquals(expectedContinuationCalls, continuationCalls)
        assertEquals(expectedSessionsDuringContinuation, sessionsDuringContinuation)
        assertEquals(expectedUpdates, storage.updateCalls)
        assertEquals(expectedRemovals, storage.removeCalls)
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
        val expectedUpdates =
            listOf(
                Triple(USER_ID, originalSession, savingSession),
                Triple(USER_ID, savingSession, restoredSession),
            )
        val calls = mutableListOf<String>()
        val storage = RecordingFlowStorage(trace = calls)
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedInputCalls: List<Pair<FlowState, FlowEvent>> = listOf(confirmation to input)
        val expectedContinuationCalls: List<Pair<FlowState, FlowEvent>> =
            listOf(saving to FlowEvent.Continue(USER_ID, originalSession.sessionId))
        val inputCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val continuationCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val sessionsDuringContinuation = mutableListOf<UserSession?>()
        val inputHandler =
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean = state is WaitingConfirmation

                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ): FlowResult {
                    calls += "input"
                    inputCalls += state to event
                    return FlowResult.Transition(saving)
                }
            }
        val continuationHandler =
            object : FlowHandler {
                override fun supports(state: FlowState): Boolean = state is Saving

                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ): FlowResult {
                    calls += "continue"
                    continuationCalls += state to event
                    sessionsDuringContinuation += storage.get(USER_ID)
                    return FlowResult.Transition(WaitingConfirmation(assertIs<Saving>(state).details))
                }
            }
        val manager = FlowSessionManager(storage, listOf(continuationHandler, inputHandler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

        // Then
        assertEquals(expectedResult, result)
        assertEquals(listOf("input", "update", "continue", "update"), calls)
        assertEquals(expectedInputCalls, inputCalls)
        assertEquals(expectedContinuationCalls, continuationCalls)
        assertEquals(expectedSessionsDuringContinuation, sessionsDuringContinuation)
        assertEquals(expectedUpdates, storage.updateCalls)
        assertEquals(restoredSession, storage.get(USER_ID))
        assertEquals(0, storage.removeCalls.size)
        assertEquals(details, confirmation.details)
        assertEquals(details, saving.details)
    }

    @ParameterizedTest
    @MethodSource("terminalResults")
    fun `failed terminal removal preserves the session without retry`(terminalResult: FlowResult) {
        // Given
        val originalSession = UserSession(state = INITIAL, createdAt = Instant.EPOCH)
        val concurrentSession = originalSession.copy(state = ALTERNATIVE)
        val expectedSession = concurrentSession.copy()
        val expectedRemovals = listOf(USER_ID to originalSession)
        val backingStorage = InMemoryFlowStorage()
        var updateCalls = 0
        val removeCalls = mutableListOf<Pair<TelegramUserId, UserSession>>()
        val storage =
            object : FlowStorage by backingStorage {
                override fun update(
                    userId: TelegramUserId,
                    expected: UserSession,
                    updated: UserSession,
                ): Boolean {
                    updateCalls++
                    return backingStorage.update(userId, expected, updated)
                }

                override fun remove(
                    userId: TelegramUserId,
                    expected: UserSession,
                ): Boolean {
                    removeCalls += userId to expected
                    backingStorage.update(userId, expected, concurrentSession)
                    return backingStorage.remove(userId, expected)
                }
            }
        val input = FlowEvent.UserInput(USER_ID, "текст")
        val expectedCalls: List<Pair<FlowState, FlowEvent>> = listOf(INITIAL to input)
        val handleCalls = mutableListOf<Pair<FlowState, FlowEvent>>()
        val handler =
            recordingHandler(
                supportsState = { it == INITIAL },
                result = terminalResult,
                calls = handleCalls,
            )
        val manager = FlowSessionManager(storage, listOf(handler))

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(input)

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals("Состояние сессии изменилось", rejected.reason)
        assertEquals(expectedCalls, handleCalls)
        assertEquals(0, updateCalls)
        assertEquals(expectedRemovals, removeCalls)
        assertEquals(expectedSession, storage.get(USER_ID))
    }

    private fun recordingHandler(
        supportsState: (FlowState) -> Boolean,
        result: FlowResult,
        calls: MutableList<Pair<FlowState, FlowEvent>>,
    ): FlowHandler =
        object : FlowHandler {
            override fun supports(state: FlowState): Boolean = supportsState(state)

            override fun handle(
                state: FlowState,
                event: FlowEvent,
            ): FlowResult {
                calls += state to event
                return result
            }
        }

    private class RecordingFlowStorage(
        private val backingStorage: FlowStorage = InMemoryFlowStorage(),
        private val trace: MutableList<String>? = null,
    ) : FlowStorage by backingStorage {
        val updateCalls = mutableListOf<Triple<TelegramUserId, UserSession, UserSession>>()
        val removeCalls = mutableListOf<Pair<TelegramUserId, UserSession>>()

        override fun update(
            userId: TelegramUserId,
            expected: UserSession,
            updated: UserSession,
        ): Boolean {
            trace?.add("update")
            updateCalls += Triple(userId, expected, updated)
            return backingStorage.update(userId, expected, updated)
        }

        override fun remove(
            userId: TelegramUserId,
            expected: UserSession,
        ): Boolean {
            trace?.add("remove")
            removeCalls += userId to expected
            return backingStorage.remove(userId, expected)
        }
    }
}
