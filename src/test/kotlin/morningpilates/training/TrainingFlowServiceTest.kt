package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import org.junit.jupiter.api.Nested
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceTest {

    private val service = TrainingFlowService()

    @Nested
    inner class DateInput {
        @Test
        fun `valid date transitions from waiting date to waiting start time`() {
            // Given
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

        @Test
        fun `invalid date is rejected without throwing an exception`() {
            // Given
            val state = WaitingDate
            val text = "не дата"

            // When
            val result = service.handle(state, text)

            // Then
            val rejected = assertIs<FlowResult.Rejected>(result)
            assertContains(rejected.reason, "Некорректная дата")
        }

        @Test
        fun `nonexistent calendar date is rejected without correction`() {
            // Given
            val state = WaitingDate
            val text = "2026-02-30"

            // When
            val result = service.handle(state, text)

            // Then
            val rejected = assertIs<FlowResult.Rejected>(result)
            assertContains(rejected.reason, "Некорректная дата")
        }
    }

    @Nested
    inner class StartTimeInput {
        @Test
        fun `valid start time transitions to adding technique and preserves date`() {
            // Given
            val date = LocalDate.of(2026, 10, 9)
            val state = WaitingStartTime(date)
            val text = "19:30"

            // When
            val result = service.handle(state, text)

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
            val result = service.handle(state, text)

            // Then
            val rejected = assertIs<FlowResult.Rejected>(result)
            assertContains(rejected.reason, "Некорректное время")
        }
    }
}
