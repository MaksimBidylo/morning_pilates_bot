package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceAddTechniqueDescriptionInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
    }

    @ParameterizedTest
    @ValueSource(strings = ["Да", "да", "ДА", "дА", " Да ", "\tДа\n"])
    fun `yes requests a description without adding a technique`(text: String) {
        // Given
        val details = populatedTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddTechniqueDescription(details, "Kimura")

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingTechniqueDescription(expectedDetails, "Kimura"), transition.nextState)
        assertEquals(expectedDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Нет", "нет", "НЕТ", "нЕт", " нет ", "\nНет\t"])
    fun `no appends a technique without description and preserves existing entries`(text: String) {
        // Given
        val details = populatedTrainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingAddTechniqueDescription(details, "Armbar")
        val expectedDetails = originalDetails.copy(
            techniques = originalDetails.techniques + TechniqueDraft(name = "Armbar", description = null),
        )

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddAnotherTechnique(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @Test
    fun `skipping description adds the first technique without a description`() {
        // Given
        val details = TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques = mutableListOf(),
        )
        val originalDetails = details.snapshot()
        val state = WaitingAddTechniqueDescription(details, "Armbar from mount")
        val expectedDetails = originalDetails.copy(
            techniques = listOf(TechniqueDraft(name = "Armbar from mount", description = null)),
        )

        // When
        val result = service.handle(state, UserInput("Нет"))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddAnotherTechnique(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "возможно", "123", "yes", "no",
            "давай", "неточно", "Да, наверное", "Да Нет", "д а",
            "", "   ", "\t", "\n", " \t\r\n ",
        ],
    )
    fun `unsupported answers are rejected without changing training details`(text: String) {
        // Given
        val details = populatedTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingAddTechniqueDescription(details, "Kimura")

        // When
        val result = service.handle(state, UserInput(text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_INVALID_CHOICE_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
        assertEquals("Kimura", state.techniqueName)
    }
}
