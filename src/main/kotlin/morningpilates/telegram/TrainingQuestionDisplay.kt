package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.network.bimap
import morningpilates.flow.model.SessionId
import morningpilates.user.TelegramUserId

internal fun Bot.updateTrainingQuestionAndDeleteInput(
    question: TrainingMessageSession,
    reply: TrainingReply,
    userMessage: Message,
    messageSessionStorage: TrainingMessageSessionStorage,
    telegramUserId: TelegramUserId,
) {
    if (updateTrainingQuestion(question, reply, messageSessionStorage, telegramUserId)) {
        deleteMessage(ChatId.fromId(userMessage.chat.id), messageId = userMessage.messageId)
    }
}

internal fun Bot.updateTrainingQuestion(
    question: TrainingMessageSession,
    reply: TrainingReply,
    messageSessionStorage: TrainingMessageSessionStorage,
    telegramUserId: TelegramUserId,
): Boolean {
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
        TelegramMessageEditStatus.Updated -> {
            messageSessionStorage.registerQuestion(telegramUserId, question, reply.callbackData())
            true
        }

        TelegramMessageEditStatus.Missing -> {
            sendTrainingQuestion(question.sessionId, question.chatId, reply, messageSessionStorage, telegramUserId)
        }

        TelegramMessageEditStatus.Failed -> {
            false
        }
    }
}

internal fun Bot.sendTrainingQuestion(
    sessionId: SessionId,
    chatId: TelegramChatId,
    reply: TrainingReply,
    messageSessionStorage: TrainingMessageSessionStorage,
    telegramUserId: TelegramUserId,
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
            sessionId = sessionId,
            chatId = TelegramChatId(sent.chat.id),
            messageId = TelegramMessageId(sent.messageId),
        )
    messageSessionStorage.put(telegramUserId, registration)
    messageSessionStorage.registerQuestion(telegramUserId, registration, reply.callbackData())
    return true
}
