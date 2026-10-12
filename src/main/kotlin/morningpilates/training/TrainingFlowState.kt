package morningpilates.training

import morningpilates.flow.model.AwaitingContinuation
import morningpilates.flow.model.FlowState
import java.time.LocalDate

sealed interface TrainingFlowState : FlowState {
    data object WaitingDate : TrainingFlowState

    data class WaitingStartTime(
        val date: LocalDate,
    ) : TrainingFlowState

    data class WaitingAddTechnique(
        val details: TrainingDetails,
    ) : TrainingFlowState

    data class WaitingTechniqueName(
        val details: TrainingDetails,
    ) : TrainingFlowState

    data class WaitingAddTechniqueDescription(
        val details: TrainingDetails,
        val techniqueName: String,
    ) : TrainingFlowState

    data class WaitingTechniqueDescription(
        val details: TrainingDetails,
        val techniqueName: String,
    ) : TrainingFlowState

    data class WaitingAddAnotherTechnique(
        val details: TrainingDetails,
    ) : TrainingFlowState

    data class WaitingAddNote(
        val details: TrainingDetails,
    ) : TrainingFlowState

    data class WaitingNote(
        val details: TrainingDetails,
    ) : TrainingFlowState

    data class WaitingConfirmation(
        val details: TrainingDetails,
    ) : TrainingFlowState

    data class Saving(
        val details: TrainingDetails,
    ) : TrainingFlowState,
        AwaitingContinuation
}
