package morningpilates.training

import morningpilates.flow.FlowEvent.UserInput
import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceTechniqueDescriptionInputTest : TrainingFlowServiceTestBase() {
    companion object {
        private const val EXPECTED_EMPTY_TECHNIQUE_DESCRIPTION_REASON = "Введите описание техники"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Описание техники",
            "Сначала  контроль, затем рычаг.",
            "Use Armbar from Mount",
            "Контролируем руку и положение корпуса.\n\nВыполняем рычаг локтя.",
            "Да",
            "Нет",
        ],
    )
    fun `description text appends a technique even when its name already exists`(text: String) {
        // Given
        val details = populatedTrainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingTechniqueDescription(details, "Armbar")
        val expectedDetails =
            originalDetails.copy(
                techniques = originalDetails.techniques + TechniqueDraft(name = "Armbar", description = text),
            )

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddAnotherTechnique(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = [" Описание техники ", "\tОписание техники\t", "\nОписание техники\r\n"])
    fun `surrounding whitespace is removed from a technique description`(text: String) {
        // Given
        val details = populatedTrainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingTechniqueDescription(details, "Kimura")
        val expectedDetails =
            originalDetails.copy(
                techniques =
                    originalDetails.techniques +
                        TechniqueDraft(
                            name = "Kimura",
                            description = "Описание техники",
                        ),
            )

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddAnotherTechnique(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\n", " \t\r\n "])
    fun `blank descriptions are rejected without adding a technique`(text: String) {
        // Given
        val details = populatedTrainingDetails()
        val expectedDetails = details.snapshot()
        val state = WaitingTechniqueDescription(details, "Kimura")

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertEquals(EXPECTED_EMPTY_TECHNIQUE_DESCRIPTION_REASON, rejected.reason)
        assertEquals(expectedDetails, state.details)
        assertEquals("Kimura", state.techniqueName)
    }

    @Test
    fun `description adds the first technique to an empty training`() {
        // Given
        val details = minimalTrainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingTechniqueDescription(details, "Armbar from mount")
        val text = "Рычаг локтя из маунта"
        val expectedDetails =
            originalDetails.copy(
                techniques = listOf(TechniqueDraft(name = "Armbar from mount", description = text)),
            )

        // When
        val result = service.handle(state, UserInput(telegramUserId, text))

        // Then
        val transition = assertIs<FlowResult.Transition>(result)
        assertEquals(WaitingAddAnotherTechnique(expectedDetails), transition.nextState)
        assertEquals(originalDetails, state.details)
    }
}
