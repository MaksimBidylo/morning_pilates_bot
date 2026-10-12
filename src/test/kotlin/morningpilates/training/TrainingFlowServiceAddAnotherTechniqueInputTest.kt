package morningpilates.training

import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceAddAnotherTechniqueInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
    }

    @Test
    fun `choosing another technique requests its name without adding a draft`() {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddAnotherTechnique(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, "Да"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingTechniqueName(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @Test
    fun `finishing techniques requests a note choice without removing a technique`() {
        // Given
        val details = trainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddAnotherTechnique(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, "Нет"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddNote(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["да", "ДА", "дА", " Да ", "\tДа\t", "\nДа\n"])
    fun `yes variants request the next technique name and preserve all training details`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingAddAnotherTechnique(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingTechniqueName(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["нет", "НЕТ", "нЕт", " нет ", "\tНет\t", "\nНет\n"])
    fun `no variants request a note choice and preserve all training details`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingAddAnotherTechnique(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddNote(expectedDetails), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "возможно", "123", "yes", "no",
            "давай", "неточно", "Да, наверное", "Да Нет", "д а",
            "", "   ", "\t", "\n", " \t\r\n ",
        ],
    )
    fun `unsupported answers are rejected without adding or removing techniques`(text: String) {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val expectedDetails = details.snapshot()
        val state = WaitingAddAnotherTechnique(details)

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_INVALID_CHOICE_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
    }
}
