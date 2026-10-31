package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.telegram.api.TelegramChatId
import morningpilates.training.TrainingFlowState
import morningpilates.user.TelegramUserId

internal fun Bot.cancelActiveFlow(
    userId: TelegramUserId,
    session: UserSession,
    chatId: TelegramChatId,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
): Boolean {
    val result = sessionManager.handle(FlowEvent.Cancel(userId))
    return showFlowCancellationResult(userId, session, chatId, result, messageSessionStorage)
}

internal fun Bot.showFlowCancellationResult(
    userId: TelegramUserId,
    session: UserSession,
    chatId: TelegramChatId,
    result: FlowResult,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
): Boolean {
    val text =
        when (result) {
            FlowResult.Cancelled -> {
                if (session.state is TrainingFlowState) "Создание тренировки отменено." else "Действие отменено."
            }

            is FlowResult.Rejected -> {
                result.reason
            }

            else -> {
                return false
            }
        }
    val question = messageSessionStorage.get(userId)?.takeIf { it.sessionId == session.sessionId }
    if (result == FlowResult.Cancelled && question != null) {
        messageSessionStorage.remove(userId, question)
    }
    return if (question == null) {
        sendMessage(ChatId.fromId(chatId.value), text = text).getOrNull() != null
    } else {
        val edited =
            editMessageText(
                chatId = ChatId.fromId(question.chatId.value),
                messageId = question.messageId.value,
                text = text,
                replyMarkup = InlineKeyboardMarkup.create(),
            )
        edited.first?.body()?.ok == true
    }
}
