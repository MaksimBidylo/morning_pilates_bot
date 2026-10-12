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
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.user.TelegramUserId

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
        )
    for ((callbackData, answer) in callbackAnswers) {
        callbackQuery(data = callbackData) {
            if (callbackQuery.data != callbackData) return@callbackQuery
            val message = callbackQuery.message ?: return@callbackQuery
            if (message.chat.type != "private") return@callbackQuery
            val telegramUserId = TelegramUserId(callbackQuery.from.id)
            if (!acceptsCallback(callbackData, storage.get(telegramUserId)?.state)) return@callbackQuery

            val result = sessionManager.handle(UserInput(telegramUserId, answer))
            if (result is FlowResult.Transition) {
                val reply = promptFor(result.nextState) ?: return@callbackQuery
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = reply.text,
                    replyMarkup = reply.replyMarkup,
                )
            }
        }
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

        else -> false
    }
