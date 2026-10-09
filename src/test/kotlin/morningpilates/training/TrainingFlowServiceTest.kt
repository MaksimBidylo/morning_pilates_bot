package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import org.junit.jupiter.api.Nested
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceTest {

    companion object {
        private const val EXPECTED_INVALID_DATE_REASON = "Некорректная дата"
        private const val EXPECTED_INVALID_TIME_REASON = "Некорректное время"
    }

    private val service = TrainingFlowService()

    private fun assertRejected(result: FlowResult, expectedReason: String) {
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, expectedReason)
    }

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
            assertRejected(result, EXPECTED_INVALID_DATE_REASON)
        }

        @Test
        fun `nonexistent calendar date is rejected without correction`() {
            // Given
            val state = WaitingDate
            val text = "2026-02-30"

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_DATE_REASON)
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
            assertRejected(result, EXPECTED_INVALID_TIME_REASON)
        }

        @Test
        fun `out of range hour is rejected without correction`() {
            // Given
            val state = WaitingStartTime(LocalDate.of(2026, 10, 9))
            val text = "25:00"

            // When
            val result = service.handle(state, text)

            // Then
            assertRejected(result, EXPECTED_INVALID_TIME_REASON)
        }
    }

    @Nested
    inner class AddTechniqueInput {
        @Test
        fun `choosing to add a technique requests its name and preserves training details`() {
            // Given
            val details = TrainingDetails(
                date = LocalDate.of(2026, 10, 9),
                time = LocalTime.of(19, 30),
                techniques = emptyList(),
                note = null,
            )
            val state = WaitingAddTechnique(details)
            val text = "Да"

            // When
            val result = service.handle(state, text)

            // Then
            val transition = assertIs<FlowResult.Transition>(result)
            assertEquals(WaitingTechniqueName(details), transition.nextState)
        }
    }
}
