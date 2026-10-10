package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.flow.FlowSessionManager
import morningpilates.flow.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingFlowSessionIntegrationTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val USER_ID = 1L
    }

    private val storage = InMemoryFlowStorage()
    private val manager = FlowSessionManager(storage, listOf(service))

    @Test
    fun `confirming a training saves all details once and removes the session`() {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)
        val originalSession = UserSession(state = state, createdAt = Instant.EPOCH)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, UserInput("Да"))

        // Then
        assertEquals(FlowResult.Completed, result)
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(expectedDetails, trainingRepository.saveCalls.single())
        assertNull(storage.get(USER_ID))
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `failed saving returns the session to confirmation with all details and creation time preserved`() {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)
        val expectedState = WaitingConfirmation(expectedDetails)
        val originalSession = UserSession(state = state, createdAt = Instant.EPOCH)
        val expectedSession = originalSession.copy(state = expectedState)

        trainingRepository.saveResult = Result.failure(IllegalStateException("Ошибка записи"))
        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, UserInput("Да"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(expectedState, transition.nextState)
        assertEquals(expectedSession, storage.get(USER_ID))
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(expectedDetails, trainingRepository.saveCalls.single())
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `declining a training removes the session without saving`() {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)
        val originalSession = UserSession(state = state, createdAt = Instant.EPOCH)

        assertTrue(storage.start(USER_ID, originalSession))

        // When
        val result = manager.handle(USER_ID, UserInput("Нет"))

        // Then
        assertEquals(FlowResult.Cancelled, result)
        assertNull(storage.get(USER_ID))
        assertTrue(trainingRepository.saveCalls.isEmpty())
        assertEquals(expectedDetails, state.details)
    }
}
