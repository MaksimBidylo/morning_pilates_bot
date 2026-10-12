package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.user.TelegramUserId

fun Dispatcher.registerAddTechniqueCallbackHandler(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
) {
    val callbackAnswers =
        listOf(
            ADD_TECHNIQUE_YES_CALLBACK to YES_BUTTON_TEXT,
            ADD_TECHNIQUE_NO_CALLBACK to NO_BUTTON_TEXT,
        )
    for ((callbackData, answer) in callbackAnswers) {
        callbackQuery(data = callbackData) {
            if (callbackQuery.data != callbackData) return@callbackQuery
            val message = callbackQuery.message ?: return@callbackQuery
            val telegramUserId = TelegramUserId(callbackQuery.from.id)
            if (storage.get(telegramUserId)?.state !is WaitingAddTechnique) return@callbackQuery

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
