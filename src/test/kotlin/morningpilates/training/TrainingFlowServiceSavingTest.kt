package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.Saving
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TrainingFlowServiceSavingTest : TrainingFlowServiceTestBase() {
    @Test
    fun `internal continuation saves a minimal training once and completes the flow`() {
        // Given
        val details = TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques = mutableListOf(),
            note = null,
        )
        val expectedDetails = details.snapshot()
        val state = Saving(details)

        // When
        val result = service.continueSaving(state)

        // Then
        assertEquals(FlowResult.Completed, result)
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(expectedDetails, trainingRepository.saveCalls.single())
        assertEquals(expectedDetails, state.details)
    }
}
