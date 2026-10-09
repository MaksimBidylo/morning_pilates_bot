package morningpilates.training

import morningpilates.flow.FlowState
import java.time.LocalDate

sealed interface TrainingFlowState : FlowState {

    data object WaitingDate : TrainingFlowState

    data class WaitingStartTime(val date: LocalDate) : TrainingFlowState

    data class WaitingAddTechnique(val details: TrainingDetails) : TrainingFlowState
}
