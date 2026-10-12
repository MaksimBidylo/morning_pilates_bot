package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.user.TelegramUserId

private const val TRAINING_SAVED_MESSAGE = "Тренировка сохранена."
private const val TRAINING_CANCELLED_MESSAGE = "Создание тренировки отменено."
private const val TRAINING_SAVE_FAILED_MESSAGE = "Не удалось сохранить тренировку. Попробуйте ещё раз."

fun Dispatcher.registerTrainingChoiceCallbacks(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
) {
    val callbackAnswers =
        listOf(
            ADD_TECHNIQUE_YES_CALLBACK to YES_BUTTON_TEXT,
            ADD_TECHNIQUE_NO_CALLBACK to NO_BUTTON_TEXT,
            ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK to YES_BUTTON_TEXT,
            ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK to NO_BUTTON_TEXT,
            ADD_ANOTHER_TECHNIQUE_YES_CALLBACK to YES_BUTTON_TEXT,
            ADD_ANOTHER_TECHNIQUE_NO_CALLBACK to NO_BUTTON_TEXT,
            ADD_NOTE_YES_CALLBACK to YES_BUTTON_TEXT,
            ADD_NOTE_NO_CALLBACK to NO_BUTTON_TEXT,
            CONFIRMATION_YES_CALLBACK to YES_BUTTON_TEXT,
            CONFIRMATION_NO_CALLBACK to NO_BUTTON_TEXT,
        )
    for ((callbackData, answer) in callbackAnswers) {
        callbackQuery(data = callbackData) {
            if (callbackQuery.data != callbackData) return@callbackQuery
            val message = callbackQuery.message ?: return@callbackQuery
            if (message.chat.type != "private") return@callbackQuery
            val telegramUserId = TelegramUserId(callbackQuery.from.id)
            if (!acceptsCallback(callbackData, storage.get(telegramUserId)?.state)) return@callbackQuery

            val result = sessionManager.handle(UserInput(telegramUserId, answer))
            val reply = replyFor(callbackData, result) ?: return@callbackQuery
            bot.sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text = reply.text,
                replyMarkup = reply.replyMarkup,
            )
        }
    }
}

private fun replyFor(
    callbackData: String,
    result: FlowResult,
): TrainingReply? {
    val reply =
        when (result) {
            is FlowResult.Transition -> promptFor(result.nextState)
            FlowResult.Completed -> TrainingReply(TRAINING_SAVED_MESSAGE)
            FlowResult.Cancelled -> TrainingReply(TRAINING_CANCELLED_MESSAGE)
            is FlowResult.Rejected -> null
        } ?: return null
    return if (callbackData == CONFIRMATION_YES_CALLBACK &&
        result is FlowResult.Transition &&
        result.nextState is WaitingConfirmation
    ) {
        reply.copy(text = TRAINING_SAVE_FAILED_MESSAGE)
    } else {
        reply
    }
}

private fun acceptsCallback(
    callbackData: String,
    state: FlowState?,
): Boolean =
    when (callbackData) {
        ADD_TECHNIQUE_YES_CALLBACK, ADD_TECHNIQUE_NO_CALLBACK -> state is WaitingAddTechnique

        ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK,
        ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK,
        -> state is WaitingAddTechniqueDescription

        ADD_ANOTHER_TECHNIQUE_YES_CALLBACK,
        ADD_ANOTHER_TECHNIQUE_NO_CALLBACK,
        -> state is WaitingAddAnotherTechnique

        ADD_NOTE_YES_CALLBACK, ADD_NOTE_NO_CALLBACK -> state is WaitingAddNote

        CONFIRMATION_YES_CALLBACK, CONFIRMATION_NO_CALLBACK -> state is WaitingConfirmation

        else -> false
    }
