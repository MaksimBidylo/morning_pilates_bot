package morningpilates.training

import morningpilates.flow.FlowResult
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import java.time.LocalDate
import java.time.LocalTime
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

    @Test
    fun `minimal training reaches confirmation with only date and time`() {
        // Given
        val state = WaitingDate
        val expectedStartTimeState = WaitingStartTime(LocalDate.of(2026, 10, 9))
        val expectedDetails = TrainingDetails(
            date = LocalDate.of(2026, 10, 9),
            time = LocalTime.of(19, 30),
            techniques = emptyList(),
            note = null,
        )

        // When
        val dateResult = service.handle(state, "2026-10-09")

        // Then
        val dateTransition = assertIs<FlowResult.Transition>(dateResult)
        val startTimeState = assertIs<WaitingStartTime>(dateTransition.nextState)
        assertEquals(expectedStartTimeState, startTimeState)

        // When
        val timeResult = service.handle(startTimeState, "19:30")

        // Then
        val timeTransition = assertIs<FlowResult.Transition>(timeResult)
        val addTechniqueState = assertIs<WaitingAddTechnique>(timeTransition.nextState)
        assertEquals(WaitingAddTechnique(expectedDetails), addTechniqueState)
        assertEquals(expectedStartTimeState, startTimeState)
        val techniqueDetailsSnapshot = addTechniqueState.details.snapshot()

        // When
        val techniqueChoiceResult = service.handle(addTechniqueState, "Нет")

        // Then
        val techniqueChoiceTransition = assertIs<FlowResult.Transition>(techniqueChoiceResult)
        val addNoteState = assertIs<WaitingAddNote>(techniqueChoiceTransition.nextState)
        assertEquals(WaitingAddNote(expectedDetails), addNoteState)
        assertEquals(expectedStartTimeState, startTimeState)
        assertEquals(techniqueDetailsSnapshot, addTechniqueState.details)
        val noteDetailsSnapshot = addNoteState.details.snapshot()

        // When
        val noteChoiceResult = service.handle(addNoteState, "Нет")

        // Then
        val noteChoiceTransition = assertIs<FlowResult.Transition>(noteChoiceResult)
        assertEquals(WaitingConfirmation(expectedDetails), noteChoiceTransition.nextState)
        assertEquals(expectedStartTimeState, startTimeState)
        assertEquals(techniqueDetailsSnapshot, addTechniqueState.details)
        assertEquals(noteDetailsSnapshot, addNoteState.details)
    }
}
