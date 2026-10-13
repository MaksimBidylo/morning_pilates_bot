package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.command.showTrainingCancellation
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.user.TelegramUserId

private const val TRAINING_SAVED_MESSAGE = "Тренировка сохранена."
private const val TRAINING_CANCELLED_MESSAGE = "Создание тренировки отменено."
private const val TRAINING_SAVE_FAILED_MESSAGE = "Не удалось сохранить тренировку. Попробуйте ещё раз."

private val CALLBACK_ANSWERS =
    mapOf(
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

fun Dispatcher.registerTrainingChoiceCallbacks(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    callbackQuery(data = "training:") {
        val callbackData = callbackQuery.data.substringBeforeLast(':')
        val message = callbackQuery.message ?: return@callbackQuery
        if (message.chat.type != "private") return@callbackQuery
        val telegramUserId = TelegramUserId(callbackQuery.from.id)
        val session = storage.get(telegramUserId) ?: return@callbackQuery
        val state = session.state
        if (!acceptsCallback(callbackData, state)) return@callbackQuery

        if (!messageSessionStorage.consumeQuestion(
                telegramUserId,
                TrainingMessageSession(
                    session.sessionId,
                    TelegramChatId(message.chat.id),
                    TelegramMessageId(message.messageId),
                ),
                callbackQuery.data,
            )
        ) {
            return@callbackQuery
        }

        if (callbackData == CANCEL_TRAINING_CALLBACK) {
            bot.cancelTraining(telegramUserId, sessionManager, messageSessionStorage)
            return@callbackQuery
        }
        val result = sessionManager.handle(UserInput(telegramUserId, CALLBACK_ANSWERS.getValue(callbackData)))
        if (bot.handleCompletedTraining(result, state, telegramUserId, messageSessionStorage)) return@callbackQuery
        val reply = (replyFor(callbackData, result) ?: return@callbackQuery).withQuestionToken()
        val question =
            if (state is WaitingConfirmation) null else messageSessionStorage.get(telegramUserId)
        if (question != null) {
            val edited =
                bot.editMessageText(
                    chatId = ChatId.fromId(question.chatId.value),
                    messageId = question.messageId.value,
                    text = reply.text,
                    replyMarkup = reply.replyMarkup ?: InlineKeyboardMarkup.create(),
                )
            if (edited.first?.body()?.ok == true) {
                messageSessionStorage.registerQuestion(telegramUserId, question, reply.callbackData())
            }
            return@callbackQuery
        }
        val sent =
            bot
                .sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = reply.text,
                    replyMarkup = reply.replyMarkup,
                ).getOrNull() ?: return@callbackQuery
        messageSessionStorage.registerQuestion(
            telegramUserId,
            TrainingMessageSession(session.sessionId, TelegramChatId(sent.chat.id), TelegramMessageId(sent.messageId)),
            reply.callbackData(),
        )
    }
}

private fun Bot.handleCompletedTraining(
    result: FlowResult,
    state: FlowState,
    userId: TelegramUserId,
    messageSessionStorage: TrainingMessageSessionStorage,
): Boolean {
    val question = messageSessionStorage.get(userId)
    if (result != FlowResult.Completed || state !is WaitingConfirmation || question == null) return false
    val reply = state.details.toSavedTrainingReply()
    val edited =
        editMessageText(
            chatId = ChatId.fromId(question.chatId.value),
            messageId = question.messageId.value,
            text = reply.text,
            parseMode = reply.parseMode,
            replyMarkup = reply.replyMarkup,
        )
    if (edited.first?.body()?.ok == true) {
        messageSessionStorage.remove(userId, question)
    }
    return true
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
        CANCEL_TRAINING_CALLBACK -> state is TrainingFlowState && state !is TrainingFlowState.Saving

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

private fun Bot.cancelTraining(
    userId: TelegramUserId,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    val question = messageSessionStorage.get(userId) ?: return
    if (sessionManager.handle(FlowEvent.Cancel(userId)) == FlowResult.Cancelled) {
        showTrainingCancellation(userId, question, messageSessionStorage)
    }
}
