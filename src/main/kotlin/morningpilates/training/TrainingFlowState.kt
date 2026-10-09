package morningpilates.training

import morningpilates.flow.FlowState

sealed interface TrainingFlowState : FlowState {

    data object WaitingDate : TrainingFlowState
}