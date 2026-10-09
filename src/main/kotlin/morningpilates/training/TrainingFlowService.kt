package morningpilates.training

import morningpilates.flow.FlowResult
import java.time.LocalDate

class TrainingFlowService {

    fun handle(state: TrainingFlowState, text: String): FlowResult = when (state) {
        TrainingFlowState.WaitingDate -> FlowResult.Transition(
            TrainingFlowState.WaitingStartTime(LocalDate.parse(text)),
        )

        else -> TODO()
    }
}
