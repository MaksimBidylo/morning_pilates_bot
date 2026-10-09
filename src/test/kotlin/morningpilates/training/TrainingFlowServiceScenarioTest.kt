package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TrainingFlowServiceScenarioTest : TrainingFlowServiceTestBase() {
    @Test
    fun `yes waits for a separate description call before adding a technique`() {
        // Given
        val details = populatedTrainingDetails()
        val originalDetails = details.snapshot()
        val state = WaitingAddTechniqueDescription(details, "Kimura")
        val text = "Контроль руки и рычаг плеча"
        val expectedDetails = originalDetails.copy(
            techniques = originalDetails.techniques + TechniqueDraft(name = "Kimura", description = text),
        )

        // When
        val choiceResult = service.handle(state, "Да")

        // Then
        val choiceTransition = assertIs<FlowResult.Transition>(choiceResult)
        val descriptionState = assertIs<WaitingTechniqueDescription>(choiceTransition.nextState)
        assertEquals(
            WaitingTechniqueDescription(originalDetails, "Kimura"),
            descriptionState,
        )
        assertEquals(originalDetails, state.details)

        // When
        val descriptionResult = service.handle(descriptionState, text)

        // Then
        val descriptionTransition = assertIs<FlowResult.Transition>(descriptionResult)
        assertEquals(
            WaitingAddAnotherTechnique(expectedDetails),
            descriptionTransition.nextState,
        )
        assertEquals(originalDetails, state.details)
        assertEquals(originalDetails, descriptionState.details)
    }

    @Test
    fun `another technique cycle appends exactly one technique and preserves all previous states`() {
        // Given
        val details = trainingDetails(note = "Заметка к тренировке")
        val originalDetails = details.snapshot()
        val state = WaitingAddAnotherTechnique(details)
        val expectedDetails = originalDetails.copy(
            techniques = originalDetails.techniques + TechniqueDraft(name = "Kimura", description = null),
        )

        // When
        val choiceResult = service.handle(state, "Да")

        // Then
        val choiceTransition = assertIs<FlowResult.Transition>(choiceResult)
        val nameState = assertIs<WaitingTechniqueName>(choiceTransition.nextState)
        assertEquals(WaitingTechniqueName(originalDetails), nameState)
        assertEquals(originalDetails, state.details)

        // When
        val nameResult = service.handle(nameState, "Kimura")

        // Then
        val nameTransition = assertIs<FlowResult.Transition>(nameResult)
        val descriptionChoiceState = assertIs<WaitingAddTechniqueDescription>(nameTransition.nextState)
        assertEquals(
            WaitingAddTechniqueDescription(originalDetails, "Kimura"),
            descriptionChoiceState,
        )
        assertEquals(originalDetails, state.details)
        assertEquals(originalDetails, nameState.details)

        // When
        val descriptionChoiceResult = service.handle(descriptionChoiceState, "Нет")

        // Then
        val finalTransition = assertIs<FlowResult.Transition>(descriptionChoiceResult)
        assertEquals(WaitingAddAnotherTechnique(expectedDetails), finalTransition.nextState)
        assertEquals(originalDetails, state.details)
        assertEquals(originalDetails, nameState.details)
        assertEquals(originalDetails, descriptionChoiceState.details)
    }
}
