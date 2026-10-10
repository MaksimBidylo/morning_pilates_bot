package morningpilates.training

import morningpilates.flow.FlowEvent.Continue
import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.Saving
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        val result = service.handle(state, Continue)

        // Then
        assertEquals(FlowResult.Completed, result)
        assertEquals(1, trainingRepository.saveCalls.size)
        assertEquals(expectedDetails, trainingRepository.saveCalls.single())
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Да", "ДА", " да ", "\tДа\n",
            "Нет", "НЕТ", " нет ", "\tНеТ\n", "/cancel",
            "Произвольный текст", "", " ", "\t", "\n", " \t\r\n ",
        ],
    )
    fun `user input while saving is rejected without calling the repository`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = Saving(details)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertTrue(rejected.reason.isNotBlank())
        assertEquals(0, trainingRepository.saveCalls.size)
        assertEquals(expectedDetails, state.details)
    }
}
