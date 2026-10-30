package morningpilates.training

import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.user.TelegramUserId
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField

private const val YES = "да"
private const val NO = "нет"
private const val INVALID_CHOICE_REASON = "Выберите «Да» или «Нет»"
internal const val INVALID_DATE_REASON =
    "Некорректная дата. Используйте формат ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. " +
        "Разделители: ., / или -. Проверьте правильность даты."
private const val INVALID_TIME_REASON =
    "Некорректное время. Используйте формат Ч:ММ, ЧЧ:ММ или аналогичный формат с дефисом. " +
        "Например: 8:00 или 19-30."
private val START_TIME_FORMATTER =
    DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

class TrainingFlowService(
    private val trainingRepository: TrainingRepository,
) : FlowHandler {
    companion object {
        private const val TECHNIQUE_NAME_REQUIRED_REASON = "Введите название техники"
        private const val TECHNIQUE_DESCRIPTION_REQUIRED_REASON = "Введите описание техники"
        private const val NOTE_REQUIRED_REASON = "Введите заметку о тренировке"
        private const val SAVING_IN_PROGRESS_REASON = "Дождитесь результата сохранения"
        private const val CANCELLATION_DURING_SAVING_REASON = "Тренировка сохраняется. Дождитесь завершения."
        private const val UNEXPECTED_CONTINUATION_REASON = "Продолжение недоступно в текущем состоянии"
        private const val UNSUPPORTED_STATE_MESSAGE = "Обработчик тренировок поддерживает только TrainingFlowState"
    }

    override fun supports(state: FlowState): Boolean = state is TrainingFlowState

    override fun handle(
        state: FlowState,
        event: FlowEvent,
    ): FlowResult {
        require(state is TrainingFlowState) {
            UNSUPPORTED_STATE_MESSAGE
        }

        return when (event) {
            is FlowEvent.Cancel -> {
                if (state is TrainingFlowState.Saving) {
                    FlowResult.Rejected(CANCELLATION_DURING_SAVING_REASON)
                } else {
                    FlowResult.Cancelled
                }
            }

            is FlowEvent.UserInput -> {
                handleUserInput(state, event.text)
            }

            is FlowEvent.Continue -> {
                when (state) {
                    is TrainingFlowState.Saving -> {
                        continueSaving(state, event.telegramUserId, event.sessionId.toTrainingId())
                    }

                    else -> {
                        FlowResult.Rejected(UNEXPECTED_CONTINUATION_REASON)
                    }
                }
            }
        }
    }

    private fun handleUserInput(
        state: TrainingFlowState,
        text: String,
    ): FlowResult =
        when (state) {
            TrainingFlowState.WaitingDate -> handleDate(text)
            is TrainingFlowState.WaitingStartTime -> handleStartTime(state, text)
            is TrainingFlowState.WaitingAddTechnique -> handleTechniqueChoice(state.details, text)
            is TrainingFlowState.WaitingTechniqueName -> handleTechniqueName(state, text)
            is TrainingFlowState.WaitingAddTechniqueDescription -> handleAddTechniqueDescription(state, text)
            is TrainingFlowState.WaitingTechniqueDescription -> handleTechniqueDescription(state, text)
            is TrainingFlowState.WaitingAddAnotherTechnique -> handleTechniqueChoice(state.details, text)
            is TrainingFlowState.WaitingAddNote -> handleAddNote(state, text)
            is TrainingFlowState.WaitingNote -> handleNote(state, text)
            is TrainingFlowState.WaitingConfirmation -> handleConfirmation(state, text)
            is TrainingFlowState.Saving -> FlowResult.Rejected(SAVING_IN_PROGRESS_REASON)
        }

    private fun continueSaving(
        state: TrainingFlowState.Saving,
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
    ): FlowResult {
        val saveResult = trainingRepository.save(telegramUserId, trainingId, state.details)
        return if (saveResult.isSuccess) {
            FlowResult.Completed
        } else {
            LoggerFactory.getLogger(TrainingFlowService::class.java).error(
                "event=storage_failure operation=training_save stage=persist handling=retry_transition",
                saveResult.exceptionOrNull(),
            )
            FlowResult.Transition(TrainingFlowState.WaitingConfirmation(state.details))
        }
    }

    private fun handleTechniqueChoice(
        details: TrainingDetails,
        text: String,
    ): FlowResult =
        handleYesNo(
            text = text,
            onYes = { FlowResult.Transition(TrainingFlowState.WaitingTechniqueName(details)) },
            onNo = { FlowResult.Transition(TrainingFlowState.WaitingAddNote(details)) },
        )

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
    ): FlowResult =
        handleYesNo(
            text = text,
            onYes = {
                FlowResult.Transition(
                    TrainingFlowState.WaitingTechniqueDescription(state.details, state.techniqueName),
                )
            },
            onNo = {
                addTechnique(
                    details = state.details,
                    technique = TechniqueDraft(name = state.techniqueName, description = null),
                )
            },
        )

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

    private fun handleAddNote(
        state: TrainingFlowState.WaitingAddNote,
        text: String,
    ): FlowResult =
        handleYesNo(
            text = text,
            onYes = { FlowResult.Transition(TrainingFlowState.WaitingNote(state.details)) },
            onNo = { FlowResult.Transition(TrainingFlowState.WaitingConfirmation(state.details)) },
        )

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
    ): FlowResult =
        handleYesNo(
            text = text,
            onYes = { FlowResult.Transition(TrainingFlowState.Saving(state.details)) },
            onNo = { FlowResult.Cancelled },
        )
}

private fun handleDate(text: String): FlowResult {
    val date = parseTrainingDate(text) ?: return FlowResult.Rejected(INVALID_DATE_REASON)
    return FlowResult.Transition(TrainingFlowState.WaitingStartTime(date))
}

private fun handleStartTime(
    state: TrainingFlowState.WaitingStartTime,
    text: String,
): FlowResult {
    val time =
        try {
            LocalTime.parse(text.trim().replace('-', ':'), START_TIME_FORMATTER)
        } catch (_: DateTimeParseException) {
            return FlowResult.Rejected(INVALID_TIME_REASON)
        }

    val details =
        TrainingDetails(
            date = state.date,
            time = time,
        )

    return FlowResult.Transition(
        TrainingFlowState.WaitingAddTechnique(details),
    )
}

private fun handleYesNo(
    text: String,
    onYes: () -> FlowResult,
    onNo: () -> FlowResult,
): FlowResult =
    when (text.trim().lowercase()) {
        YES -> onYes()
        NO -> onNo()
        else -> FlowResult.Rejected(INVALID_CHOICE_REASON)
    }

private fun addTechnique(
    details: TrainingDetails,
    technique: TechniqueDraft,
): FlowResult.Transition {
    val updatedDetails =
        details.copy(
            techniques = details.techniques + technique,
        )

    return FlowResult.Transition(
        TrainingFlowState.WaitingAddAnotherTechnique(updatedDetails),
    )
}
