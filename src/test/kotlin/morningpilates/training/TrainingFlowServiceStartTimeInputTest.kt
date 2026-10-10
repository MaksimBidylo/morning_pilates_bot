package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingStartTime
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceStartTimeInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_TIME_REASON = "Некорректное время"
    }

    @Test
    fun `valid start time transitions to adding technique and preserves date`() {
        // Given
        val date = LocalDate.of(2026, 10, 9)
        val state = WaitingStartTime(date)
        val text = "19:30"

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechnique(
                TrainingDetails(
                    date = date,
                    time = LocalTime.of(19, 30),
                    techniques = emptyList(),
                    note = null,
                ),
            ),
            transition.nextState,
        )
    }

    @Test
    fun `invalid start time is rejected without throwing an exception`() {
        // Given
        val state = WaitingStartTime(LocalDate.of(2026, 10, 9))
        val text = "не время"

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        assertRejected(result, EXPECTED_INVALID_TIME_REASON)
    }

    @Test
    fun `out of range hour is rejected without correction`() {
        // Given
        val state = WaitingStartTime(LocalDate.of(2026, 10, 9))
        val text = "25:00"

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        assertRejected(result, EXPECTED_INVALID_TIME_REASON)
    }
}
