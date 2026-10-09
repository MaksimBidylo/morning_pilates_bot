package morningpilates.training

import morningpilates.flow.FlowResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

class TrainingFlowService {

    fun handle(state: TrainingFlowState, text: String): FlowResult = when (state) {
        TrainingFlowState.WaitingDate -> handleDate(text)
        is TrainingFlowState.WaitingStartTime -> handleStartTime(state, text)
        is TrainingFlowState.WaitingAddTechnique -> handleAddTechnique(state, text)
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
        val time = try {
            LocalTime.parse(text)
        } catch (_: DateTimeParseException) {
            return FlowResult.Rejected("Некорректное время")
        }

        val details = TrainingDetails(
            date = state.date,
            time = time,
        )

        return FlowResult.Transition(
            TrainingFlowState.WaitingAddTechnique(details),
        )
    }

    private fun handleAddTechnique(
        state: TrainingFlowState.WaitingAddTechnique,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        "да" -> FlowResult.Transition(
            TrainingFlowState.WaitingTechniqueName(state.details),
        )

        "нет" -> FlowResult.Transition(
            TrainingFlowState.WaitingAddNote(state.details),
        )

        else -> FlowResult.Rejected("Выберите «Да» или «Нет»")
    }
}
