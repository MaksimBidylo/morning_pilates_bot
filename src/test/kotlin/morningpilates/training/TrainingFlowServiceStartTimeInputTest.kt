package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingStartTime
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
        val result = service.handle(state, UserInput(telegramUserId, text))

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
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertRejected(result, EXPECTED_INVALID_TIME_REASON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["08:30:00", "08:30:00.123456789", "8:30", "08:3"])
    fun `start time outside HH mm format is rejected`(text: String) {
        // Given
        val state = WaitingStartTime(LocalDate.of(2026, 10, 9))

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertRejected(result, EXPECTED_INVALID_TIME_REASON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["24:00", "25:00", "19:60"])
    fun `out of range start time is rejected without correction`(text: String) {
        // Given
        val state = WaitingStartTime(LocalDate.of(2026, 10, 9))

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertRejected(result, EXPECTED_INVALID_TIME_REASON)
    }
}
