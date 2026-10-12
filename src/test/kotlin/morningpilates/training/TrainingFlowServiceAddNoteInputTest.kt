package morningpilates.training

import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingNote
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceAddNoteInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
    }

    @Test
    fun `choosing a note requests its text without writing a draft`() {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, "Да"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingNote(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `skipping a note requests confirmation without changing training details`() {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, "Нет"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["да", "ДА", "дА", " Да ", "\tДа\t", "\nДа\n"])
    fun `yes variants request note text and preserve all training details`(text: String) {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingNote(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["нет", "НЕТ", "нЕт", " нет ", "\tНет\t", "\nНет\n"])
    fun `no variants request confirmation and preserve all training details`(text: String) {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "возможно", "123", "yes", "no",
            "давай", "неточно", "Да, наверное", "Да Нет", "д а",
            "Сегодня отрабатывали контроль позиции",
            "", "   ", "\t", "\n", " \t\r\n ",
        ],
    )
    fun `unsupported answers and note text are rejected without changing training details`(text: String) {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_INVALID_CHOICE_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `note text can be requested when training has no techniques`() {
        // Given
        val details = minimalTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, "Да"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingNote(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `training without techniques or note can proceed to confirmation`() {
        // Given
        val details = minimalTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddNote(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, "Нет"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingConfirmation(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }
}
