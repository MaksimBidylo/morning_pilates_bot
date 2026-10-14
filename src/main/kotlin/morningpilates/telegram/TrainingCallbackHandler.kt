package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandler
import com.github.kotlintelegrambot.dispatcher.handlers.HandleCallbackQuery
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Update
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.user.TelegramUserId

private const val STALE_CALLBACK_NOTICE = "Этот диалог больше неактуален. Используйте меню команд."
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
    val currentHandler =
        CallbackQueryHandler(
            callbackData = "training:",
            handleCallbackQuery = trainingChoiceCallback(storage, sessionManager, messageSessionStorage),
        )
    registerTrainingCallbackHandler(currentHandler, messageSessionStorage)
}

private fun trainingChoiceCallback(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
): HandleCallbackQuery =
    handle@{
        val callbackData = callbackQuery.data.substringBeforeLast(':')
        val message = callbackQuery.message ?: return@handle
        if (message.chat.type != "private") return@handle
        val telegramUserId = TelegramUserId(callbackQuery.from.id)
        val session = storage.get(telegramUserId) ?: return@handle
        val state = session.state
        if (!acceptsCallback(callbackData, state)) return@handle

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
            return@handle
        }

        if (callbackData == CANCEL_TRAINING_CALLBACK) {
            bot.cancelActiveFlow(
                telegramUserId,
                session,
                TelegramChatId(message.chat.id),
                sessionManager,
                messageSessionStorage,
            )
            return@handle
        }
        val result = sessionManager.handle(UserInput(telegramUserId, CALLBACK_ANSWERS.getValue(callbackData)))
        if (bot.handleFinishedTraining(result, session, telegramUserId, messageSessionStorage)) return@handle
        val reply =
            (replyFor(callbackData, result) ?: return@handle)
                .withQuestionToken(messageSessionStorage.newQuestionToken())
        val question =
            if (result == FlowResult.Cancelled) null else messageSessionStorage.get(telegramUserId)
        if (question != null) {
            bot.updateTrainingQuestion(question, reply, messageSessionStorage, telegramUserId)
            return@handle
        }
        val sent =
            bot
                .sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = reply.text,
                    replyMarkup = reply.replyMarkup,
                ).getOrNull() ?: return@handle
        val sentMessageId = TelegramMessageId(sent.messageId)
        val registration = TrainingMessageSession(session.sessionId, TelegramChatId(sent.chat.id), sentMessageId)
        messageSessionStorage.registerQuestion(telegramUserId, registration, reply.callbackData())
    }

private fun Dispatcher.registerTrainingCallbackHandler(
    currentHandler: CallbackQueryHandler,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    val previousLaunchHandler =
        CallbackQueryHandler(callbackAnswerText = STALE_CALLBACK_NOTICE, handleCallbackQuery = {})
    addHandler(
        object : Handler by currentHandler {
            override suspend fun handleUpdate(
                bot: Bot,
                update: Update,
            ) {
                val callback = checkNotNull(update.callbackQuery)
                val selectedHandler =
                    if (messageSessionStorage.isFromPreviousLaunch(callback.data)) {
                        previousLaunchHandler
                    } else {
                        currentHandler
                    }
                selectedHandler.handleUpdate(bot, update)
            }
        },
    )
}

private fun Bot.handleFinishedTraining(
    result: FlowResult,
    session: UserSession,
    userId: TelegramUserId,
    messageSessionStorage: TrainingMessageSessionStorage,
): Boolean {
    val state = session.state
    val question = messageSessionStorage.get(userId)
    if (state !is WaitingConfirmation || question == null) return false
    return when (result) {
        FlowResult.Cancelled -> {
            showFlowCancellationResult(userId, session, question.chatId, result, messageSessionStorage)
            true
        }

        FlowResult.Completed -> {
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
            true
        }

        else -> {
            false
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
