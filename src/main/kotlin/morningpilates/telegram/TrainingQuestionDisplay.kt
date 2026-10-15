package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.network.bimap
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState
import morningpilates.user.TelegramUserId

internal fun Bot.updateTrainingQuestionAndDeleteInput(
    session: UserSession,
    reply: TrainingReply,
    userMessage: Message,
    messageSessionStorage: TrainingMessageSessionStorage,
    telegramUserId: TelegramUserId,
    flowStorage: FlowStorage,
) {
    if (updateTrainingQuestion(session, reply, messageSessionStorage, telegramUserId, flowStorage)) {
        deleteMessage(ChatId.fromId(userMessage.chat.id), messageId = userMessage.messageId)
    }
}

internal fun Bot.updateTrainingQuestion(
    session: UserSession,
    reply: TrainingReply,
    messageSessionStorage: TrainingMessageSessionStorage,
    telegramUserId: TelegramUserId,
    flowStorage: FlowStorage,
): Boolean {
    val pending = PendingTrainingQuestion(session, telegramUserId, flowStorage, messageSessionStorage)
    val question =
        pending.expected
            ?.registration
            ?.takeIf { it.sessionId == session.sessionId && session.state is TrainingFlowState }
            ?: return false
    val result =
        editMessageText(
            chatId = ChatId.fromId(question.chatId.value),
            messageId = question.messageId.value,
            text = reply.text,
            replyMarkup = reply.replyMarkup,
            parseMode = reply.parseMode,
        )
    val editStatus =
        result.bimap(
            mapResponse = { response ->
                if (response?.ok == true) TelegramMessageEditStatus.Updated else TelegramMessageEditStatus.Failed
            },
            mapError = { error -> error.messageEditStatus(result.first?.code()) },
        )
    return when (editStatus) {
        TelegramMessageEditStatus.Updated -> pending.register(question, reply)
        TelegramMessageEditStatus.Missing -> sendTrainingQuestion(pending, question.chatId, reply)
        TelegramMessageEditStatus.Failed -> false
    }
}

internal fun Bot.sendTrainingQuestion(
    session: UserSession,
    chatId: TelegramChatId,
    reply: TrainingReply,
    messageSessionStorage: TrainingMessageSessionStorage,
    telegramUserId: TelegramUserId,
    flowStorage: FlowStorage,
): Boolean {
    if (session.state !is TrainingFlowState) return false
    val pending = PendingTrainingQuestion(session, telegramUserId, flowStorage, messageSessionStorage)
    return sendTrainingQuestion(pending, chatId, reply)
}

private fun Bot.sendTrainingQuestion(
    pending: PendingTrainingQuestion,
    chatId: TelegramChatId,
    reply: TrainingReply,
): Boolean {
    val sent =
        sendMessage(
            chatId = ChatId.fromId(chatId.value),
            text = reply.text,
            replyMarkup = reply.replyMarkup,
            parseMode = reply.parseMode,
        ).getOrNull() ?: return false
    val registration =
        TrainingMessageSession(
            sessionId = pending.session.sessionId,
            chatId = TelegramChatId(sent.chat.id),
            messageId = TelegramMessageId(sent.messageId),
        )
    val registered = pending.register(registration, reply)
    if (!registered) retireTrainingQuestion(registration)
    return registered
}

private class PendingTrainingQuestion(
    val session: UserSession,
    private val userId: TelegramUserId,
    private val flowStorage: FlowStorage,
    private val messages: TrainingMessageSessionStorage,
) {
    val expected = messages.snapshot(userId)

    fun register(
        question: TrainingMessageSession,
        reply: TrainingReply,
    ): Boolean =
        flowStorage.withCurrentSession(userId, session) {
            messages.putQuestionIfCurrent(userId, expected, question, reply.callbackData())
        }
}

private fun Bot.retireTrainingQuestion(question: TrainingMessageSession) {
    val chatId = ChatId.fromId(question.chatId.value)
    if (deleteMessage(chatId, question.messageId.value).getOrNull() != true) {
        editMessageReplyMarkup(
            chatId = chatId,
            messageId = question.messageId.value,
            replyMarkup = InlineKeyboardMarkup.create(),
        )
    }
}
