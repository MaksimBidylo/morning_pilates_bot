package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.api.TelegramChatId
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingNote
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId

private val NO_ACTIVE_ACTION_MENU =
    """
    Сейчас нет активного действия.

    Выберите, что хотите сделать:

    /training_add — записать тренировку
    """.trimIndent()

fun Dispatcher.registerTrainingCreationTextHandler(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
) {
    text {
        if (message.chat.type != "private") return@text
        if (text.startsWith("/")) return@text

        val sender = message.from ?: return@text
        val telegramUserId = TelegramUserId(sender.id)
        val previousSession = storage.get(telegramUserId)
        val state = previousSession?.state
        if (state == null) {
            bot.sendMessage(ChatId.fromId(message.chat.id), text = NO_ACTIVE_ACTION_MENU)
            return@text
        }
        when (state) {
            WaitingDate,
            is WaitingStartTime,
            is WaitingTechniqueName,
            is WaitingTechniqueDescription,
            is WaitingNote,
            -> Unit

            else -> return@text
        }

        val result = sessionManager.handle(UserInput(telegramUserId, text))
        val session = storage.sessionAfterInput(telegramUserId, previousSession, result) ?: return@text
        val question = messageSessionStorage.get(telegramUserId)
        val reply =
            (result.toTrainingCreationReply(state) ?: return@text)
                .withCreationQuestionToken(
                    (if (result is FlowResult.Rejected) messageSessionStorage.questionToken(telegramUserId) else null)
                        ?: messageSessionStorage.newQuestionToken(),
                )

        if (question != null) {
            if (result is FlowResult.Transition) messageSessionStorage.invalidateQuestion(telegramUserId, question)
            bot.updateTrainingCreationQuestionAndDeleteInput(
                session,
                reply,
                message,
                messageSessionStorage,
                telegramUserId,
                storage,
            )
            return@text
        }

        if (bot.sendTrainingCreationQuestion(
                session,
                TelegramChatId(message.chat.id),
                reply,
                messageSessionStorage,
                telegramUserId,
                storage,
            )
        ) {
            bot.deleteMessage(ChatId.fromId(message.chat.id), messageId = message.messageId)
        }
    }
}

private fun FlowStorage.sessionAfterInput(
    userId: TelegramUserId,
    previous: UserSession,
    result: FlowResult,
): UserSession? {
    val nextState = if (result is FlowResult.Transition) result.nextState else previous.state
    return get(userId)?.takeIf { it.sessionId == previous.sessionId && it.state == nextState }
}
