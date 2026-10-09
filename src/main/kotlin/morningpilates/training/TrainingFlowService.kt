package morningpilates.training

import morningpilates.flow.FlowResult
import java.time.LocalDate
import java.time.format.DateTimeParseException

class TrainingFlowService {

    fun handle(state: TrainingFlowState, text: String): FlowResult = when (state) {
        TrainingFlowState.WaitingDate -> handleDate(text)
        else -> TODO()
    }

    private fun handleDate(text: String): FlowResult = try {
        FlowResult.Transition(
            TrainingFlowState.WaitingStartTime(LocalDate.parse(text)),
        )
    } catch (_: DateTimeParseException) {
        FlowResult.Rejected("Некорректная дата")
    }
}
