package morningpilates.training

import morningpilates.flow.FlowResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

class TrainingFlowService {
    companion object {
        private const val YES = "да"
        private const val NO = "нет"

        private const val INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
        private const val INVALID_DATE_REASON = "Некорректная дата"
        private const val INVALID_TIME_REASON = "Некорректное время"
        private const val TECHNIQUE_NAME_REQUIRED_REASON = "Введите название техники"
        private const val TECHNIQUE_DESCRIPTION_REQUIRED_REASON = "Введите описание техники"
        private const val NOTE_REQUIRED_REASON = "Введите заметку о тренировке"
    }

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
        is TrainingFlowState.WaitingConfirmation -> handleConfirmation(state, text)
        else -> TODO()
    }

    private fun handleDate(text: String): FlowResult = try {
        FlowResult.Transition(
            TrainingFlowState.WaitingStartTime(LocalDate.parse(text)),
        )
    } catch (_: DateTimeParseException) {
        FlowResult.Rejected(INVALID_DATE_REASON)
    }

    private fun handleStartTime(
        state: TrainingFlowState.WaitingStartTime,
        text: String,
    ): FlowResult {
        val time = try {
            LocalTime.parse(text)
        } catch (_: DateTimeParseException) {
            return FlowResult.Rejected(INVALID_TIME_REASON)
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
        YES -> FlowResult.Transition(
            TrainingFlowState.WaitingTechniqueName(state.details),
        )

        NO -> FlowResult.Transition(
            TrainingFlowState.WaitingAddNote(state.details),
        )

        else -> FlowResult.Rejected(INVALID_CHOICE_REASON)
    }

    private fun handleTechniqueName(
        state: TrainingFlowState.WaitingTechniqueName,
        text: String,
    ): FlowResult {
        val techniqueName = text.trim()
        if (techniqueName.isBlank()) {
            return FlowResult.Rejected(TECHNIQUE_NAME_REQUIRED_REASON)
        }

        return FlowResult.Transition(
            TrainingFlowState.WaitingAddTechniqueDescription(state.details, techniqueName),
        )
    }

    private fun handleAddTechniqueDescription(
        state: TrainingFlowState.WaitingAddTechniqueDescription,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        YES -> FlowResult.Transition(
            TrainingFlowState.WaitingTechniqueDescription(state.details, state.techniqueName),
        )

        NO -> addTechnique(
            details = state.details,
            technique = TechniqueDraft(name = state.techniqueName, description = null),
        )

        else -> FlowResult.Rejected(INVALID_CHOICE_REASON)
    }

    private fun handleTechniqueDescription(
        state: TrainingFlowState.WaitingTechniqueDescription,
        text: String,
    ): FlowResult {
        val description = text.trim()
        if (description.isBlank()) {
            return FlowResult.Rejected(TECHNIQUE_DESCRIPTION_REQUIRED_REASON)
        }

        val technique = TechniqueDraft(name = state.techniqueName, description = description)
        return addTechnique(state.details, technique)
    }

    private fun handleAddAnotherTechnique(
        state: TrainingFlowState.WaitingAddAnotherTechnique,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        YES -> FlowResult.Transition(
            TrainingFlowState.WaitingTechniqueName(state.details),
        )

        NO -> FlowResult.Transition(
            TrainingFlowState.WaitingAddNote(state.details),
        )

        else -> FlowResult.Rejected(INVALID_CHOICE_REASON)
    }

    private fun handleAddNote(
        state: TrainingFlowState.WaitingAddNote,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        YES -> FlowResult.Transition(
            TrainingFlowState.WaitingNote(state.details),
        )

        NO -> FlowResult.Transition(
            TrainingFlowState.WaitingConfirmation(state.details),
        )

        else -> FlowResult.Rejected(INVALID_CHOICE_REASON)
    }

    private fun handleNote(
        state: TrainingFlowState.WaitingNote,
        text: String,
    ): FlowResult {
        val note = text.trim()
        if (note.isBlank()) {
            return FlowResult.Rejected(NOTE_REQUIRED_REASON)
        }

        val details = state.details.copy(note = note)
        return FlowResult.Transition(
            TrainingFlowState.WaitingConfirmation(details),
        )
    }

    private fun handleConfirmation(
        state: TrainingFlowState.WaitingConfirmation,
        text: String,
    ): FlowResult = when (text.trim().lowercase()) {
        YES -> FlowResult.Transition(
            TrainingFlowState.Saving(state.details),
        )

        NO -> FlowResult.Cancelled

        else -> FlowResult.Rejected(INVALID_CHOICE_REASON)
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
