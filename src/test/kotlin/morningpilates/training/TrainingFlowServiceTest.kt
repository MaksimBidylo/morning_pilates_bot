package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceTest {

    @Test
    fun `valid date transitions from waiting date to waiting start time`() {
        // Given
        val service = TrainingFlowService()
        val state = WaitingDate
        val text = "2026-10-09"

        // When
        val result = service.handle(state, text)

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingStartTime(LocalDate.of(2026, 10, 9)),
            transition.nextState,
        )
    }
}
