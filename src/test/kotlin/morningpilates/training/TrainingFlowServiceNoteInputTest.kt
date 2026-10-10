package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingNote
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceNoteInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_EMPTY_NOTE_REASON = "Введите заметку о тренировке"
    }

    @Test
    fun `valid note updates only the next training state and preserves techniques`() {
        // Given
        val details = trainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingNote(details)
        val text = "В позиционных схватках начинали из half guard. Следить за контролем головы."
        val expectedDetails = originalDetails.copy(note = text)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Следить за контролем головы.",
            "Keep the Underhook!",
            "Работали half guard — 3 раунда.",
            "Сначала  контроль, затем выход.",
            "Контроль головы и положения корпуса.\n\nВыход из нижней позиции.",
        ],
    )
    fun `note content preserves case punctuation internal spaces and paragraphs`(text: String) {
        // Given
        val details = trainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingNote(details)
        val expectedDetails = originalDetails.copy(note = text)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            " Следить за контролем головы. ",
            "\tСледить за контролем головы.\t",
            "\nСледить за контролем головы.\r\n",
        ],
    )
    fun `surrounding whitespace is removed from a note`(text: String) {
        // Given
        val details = trainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingNote(details)
        val expectedDetails = originalDetails.copy(note = "Следить за контролем головы.")

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @Test
    fun `outer newlines are removed while the blank line between paragraphs is preserved`() {
        // Given
        val details = trainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingNote(details)
        val text = "\n\nКонтроль головы и положения корпуса.\n\nВыход из нижней позиции.\r\n"
        val expectedDetails =
            originalDetails.copy(
                note = "Контроль головы и положения корпуса.\n\nВыход из нижней позиции.",
            )

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\n", " \t\r\n "])
    fun `blank notes are rejected without changing training details`(text: String) {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingNote(details)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_EMPTY_NOTE_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Да", "Нет"])
    fun `yes and no are saved as ordinary note text`(text: String) {
        // Given
        val details = trainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingNote(details)
        val expectedDetails = originalDetails.copy(note = text)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @Test
    fun `a note can be added to training without techniques`() {
        // Given
        val details = minimalTrainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingNote(details)
        val text = "Открытый ковёр, пять раундов."
        val expectedDetails = originalDetails.copy(note = text)

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }
}
