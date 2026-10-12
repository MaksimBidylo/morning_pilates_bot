package morningpilates.training

import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceDateInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_DATE_REASON =
            "Некорректная дата. Используйте формат ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. " +
                "Разделители: ., / или -. Проверьте правильность даты."
    }

    @ParameterizedTest(name = "accepts date {0}")
    @ValueSource(
        strings = [
            "11.10.2026",
            "11/10/2026",
            "11-10-2026",
            "2026.10.11",
            "2026/10/11",
            "2026-10-11",
            " 11.10.2026 ",
            " 2026-10-11 ",
            "\t2026/10/11\n",
        ],
    )
    fun `equivalent date inputs transition to the same waiting start time`(text: String) {
        // Given
        val state = WaitingDate

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingStartTime(LocalDate.of(2026, 10, 11)),
            transition.nextState,
        )
    }

    @ParameterizedTest(name = "accepts leap date {0}")
    @ValueSource(strings = ["29.02.2024", "2024-02-29"])
    fun `valid leap day transitions to waiting start time without correction`(text: String) {
        // Given
        val state = WaitingDate

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(
            WaitingStartTime(LocalDate.of(2024, 2, 29)),
            transition.nextState,
        )
    }

    @ParameterizedTest(name = "rejects date {0}")
    @ValueSource(
        strings = [
            "31.02.2026",
            "2026-02-30",
            "29/02/2025",
            "2025-02-29",
            "11.10/2026",
            "2026-10/11",
            "1.10.2026",
            "11.1.2026",
            "11.10.26",
            "2026-1-11",
            "2026-10-1",
            "026-10-11",
            "02026-10-11",
            "10/31/2026",
            "не дата",
        ],
    )
    fun `invalid date is rejected without throwing an exception or correcting input`(text: String) {
        // Given
        val state = WaitingDate

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        assertEquals(FlowResult.Rejected(EXPECTED_INVALID_DATE_REASON), result)
    }
}
