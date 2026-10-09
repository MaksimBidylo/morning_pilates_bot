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
        is TrainingFlowState.WaitingTechniqueName -> handleTechniqueName(state, text)
        is TrainingFlowState.WaitingAddTechniqueDescription -> handleAddTechniqueDescription(state, text)
        is TrainingFlowState.WaitingTechniqueDescription -> handleTechniqueDescription(state, text)
        is TrainingFlowState.WaitingAddAnotherTechnique -> handleAddAnotherTechnique(state, text)
        is TrainingFlowState.WaitingAddNote -> handleAddNote(state, text)
        is TrainingFlowState.WaitingNote -> handleNote(state, text)
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

    private fun handleTechniqueName(
        state: TrainingFlowState.WaitingTechniqueName,
        text: String,
    ): FlowResult {
        val techniqueName = text.trim()
        if (techniqueName.isBlank()) {
            return FlowResult.Rejected("Введите название техники")
        }

        return FlowResult.Transition(
            TrainingFlowState.WaitingAddTechniqueDescription(state.details, techniqueName),
        )
    }

    private fun handleAddTechniqueDescription(
        state: TrainingFlowState.WaitingAddTechniqueDescription,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        "да" -> FlowResult.Transition(
            TrainingFlowState.WaitingTechniqueDescription(state.details, state.techniqueName),
        )

        "нет" -> addTechnique(
            details = state.details,
            technique = TechniqueDraft(name = state.techniqueName, description = null),
        )

        else -> FlowResult.Rejected("Выберите «Да» или «Нет»")
    }

    private fun handleTechniqueDescription(
        state: TrainingFlowState.WaitingTechniqueDescription,
        text: String,
    ): FlowResult {
        val description = text.trim()
        if (description.isBlank()) {
            return FlowResult.Rejected("Введите описание техники")
        }

        val technique = TechniqueDraft(name = state.techniqueName, description = description)
        return addTechnique(state.details, technique)
    }

    private fun handleAddAnotherTechnique(
        state: TrainingFlowState.WaitingAddAnotherTechnique,
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

    private fun handleAddNote(
        state: TrainingFlowState.WaitingAddNote,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        "да" -> FlowResult.Transition(
            TrainingFlowState.WaitingNote(state.details),
        )

        "нет" -> FlowResult.Transition(
            TrainingFlowState.WaitingConfirmation(state.details),
        )

        else -> FlowResult.Rejected("Выберите «Да» или «Нет»")
    }

    private fun handleNote(
        state: TrainingFlowState.WaitingNote,
        text: String,
    ): FlowResult {
        val note = text.trim()
        if (note.isBlank()) {
            return FlowResult.Rejected("Введите заметку о тренировке")
        }

        val details = state.details.copy(note = note)
        return FlowResult.Transition(
            TrainingFlowState.WaitingConfirmation(details),
        )
    }

    private fun addTechnique(
        details: TrainingDetails,
        technique: TechniqueDraft,
    ): FlowResult.Transition {
        val updatedDetails = details.copy(
            techniques = details.techniques + technique,
        )

        return FlowResult.Transition(
            TrainingFlowState.WaitingAddAnotherTechnique(updatedDetails),
        )
    }
}
