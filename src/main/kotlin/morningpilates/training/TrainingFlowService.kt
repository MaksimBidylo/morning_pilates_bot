package morningpilates.training

import morningpilates.flow.FlowResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

class TrainingFlowService {

    fun handle(state: TrainingFlowState, text: String): FlowResult = when (state) {
        TrainingFlowState.WaitingDate -> handleDate(text)
        is TrainingFlowState.WaitingStartTime -> handleStartTime(state, text)
        else -> TODO()
    }

    private fun handleDate(text: String): FlowResult = try {
        FlowResult.Transition(
            TrainingFlowState.WaitingStartTime(LocalDate.parse(text)),
        )
    } catch (_: DateTimeParseException) {
        FlowResult.Rejected("Некорректная дата")
    }

    private fun handleStartTime(
        state: TrainingFlowState.WaitingStartTime,
        text: String,
    ): FlowResult {
        val details = TrainingDetails(
            date = state.date,
            time = LocalTime.parse(text),
        )

        return FlowResult.Transition(
            TrainingFlowState.WaitingAddTechnique(details),
        )
    }
}
