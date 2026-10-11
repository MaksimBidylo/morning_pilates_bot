package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.flow.FlowSessionManager
import morningpilates.flow.UserSession
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository.SaveCall
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingFlowSessionIntegrationTest : TrainingFlowServiceTestBase() {
    companion object {
        @JvmStatic
        fun invalidDateAndTimeInputs(): List<Arguments> =
            listOf(
                Arguments.of(WaitingDate, "31.02.2026", "Некорректная дата"),
                Arguments.of(
                    WaitingStartTime(LocalDate.of(2026, 10, 11)),
                    "24:00",
                    "Некорректное время",
                ),
            )
    }

    private val storage = InMemoryFlowStorage()
    private val manager = FlowSessionManager(storage, listOf(service))

    @ParameterizedTest(name = "rejects {1} and preserves the session in {0}")
    @MethodSource("invalidDateAndTimeInputs")
    fun `invalid date or time preserves the session without saving`(
        state: TrainingFlowState,
        text: String,
        expectedReason: String,
    ) {
        // Given
        val originalSession = UserSession(state = state, createdAt = Instant.EPOCH)
        assertTrue(storage.start(telegramUserId, originalSession))

        // When
        val result = manager.handle(UserInput(telegramUserId, text))

        // Then
        assertRejected(result, expectedReason)
        assertEquals(originalSession, storage.get(telegramUserId))
        assertTrue(trainingRepository.saveCalls.isEmpty())
    }

    @Test
    fun `confirming a training saves all details once and removes the session`() {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)
        val originalSession = UserSession(state = state, createdAt = Instant.EPOCH)

        assertTrue(storage.start(telegramUserId, originalSession))

        // When
        val result = manager.handle(UserInput(telegramUserId, "Да"))

        // Then
        assertEquals(FlowResult.Completed, result)
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(
            SaveCall(telegramUserId, originalSession.sessionId.toTrainingId(), expectedDetails),
            trainingRepository.saveCalls.single(),
        )
        assertNull(storage.get(telegramUserId))
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
        assertTrue(storage.start(telegramUserId, originalSession))

        // When
        val result = manager.handle(UserInput(telegramUserId, "Да"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(expectedState, transition.nextState)
        assertEquals(expectedSession, storage.get(telegramUserId))
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(
            SaveCall(telegramUserId, originalSession.sessionId.toTrainingId(), expectedDetails),
            trainingRepository.saveCalls.single(),
        )
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `declining a training removes the session without saving`() {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingConfirmation(details)
        val originalSession = UserSession(state = state, createdAt = Instant.EPOCH)

        assertTrue(storage.start(telegramUserId, originalSession))

        // When
        val result = manager.handle(UserInput(telegramUserId, "Нет"))

        // Then
        assertEquals(FlowResult.Cancelled, result)
        assertNull(storage.get(telegramUserId))
        assertTrue(trainingRepository.saveCalls.isEmpty())
        assertEquals(expectedDetails, state.details)
    }
}
