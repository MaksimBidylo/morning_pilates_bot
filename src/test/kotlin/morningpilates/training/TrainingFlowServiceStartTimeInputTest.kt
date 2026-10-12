package morningpilates.training

import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingStartTime
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceStartTimeInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_TIME_REASON =
            "Некорректное время. Используйте формат Ч:ММ, ЧЧ:ММ или аналогичный формат с дефисом. " +
                "Например: 8:00 или 19-30."
    }

    @ParameterizedTest(name = "accepts start time {0}")
    @CsvSource(
        "8:00, 8, 0",
        "08:00, 8, 0",
        "8-00, 8, 0",
        "08-00, 8, 0",
        "9:30, 9, 30",
        "8:30, 8, 30",
        "19:30, 19, 30",
        "19-30, 19, 30",
        "00:00, 0, 0",
        "23:59, 23, 59",
        "' 8:00 ', 8, 0",
        "' 19-30 ', 19, 30",
    )
    fun `valid start time transitions to adding technique and preserves date`(
        text: String,
        hour: Int,
        minute: Int,
    ) {
        // Given
        val date = LocalDate.of(2026, 10, 9)
        val state = WaitingStartTime(date)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingAddTechnique(
                TrainingDetails(
                    date = date,
                    time = LocalTime.of(hour, minute),
                    techniques = emptyList(),
                    note = null,
                ),
            ),
            transition.nextState,
        )
    }

    @ParameterizedTest(name = "rejects start time {0}")
    @ValueSource(
        strings = [
            "8:0",
            "08:3",
            "008:00",
            "24:00",
            "25:00",
            "19:60",
            "08:30:00",
            "08:30:00.123",
            "8/30",
            "не время",
        ],
    )
    fun `invalid start time is rejected without throwing an exception`(text: String) {
        // Given
        val state = WaitingStartTime(LocalDate.of(2026, 10, 9))

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertEquals(FlowResult.Rejected(EXPECTED_INVALID_TIME_REASON), result)
    }
}
