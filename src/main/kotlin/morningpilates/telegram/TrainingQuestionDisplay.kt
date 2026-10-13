package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.network.ResponseError
import com.github.kotlintelegrambot.network.bimap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import morningpilates.flow.model.SessionId
import morningpilates.user.TelegramUserId

private const val MESSAGE_NOT_MODIFIED = "Bad Request: message is not modified"
private const val MESSAGE_NOT_FOUND = "Bad Request: message to edit not found"
private const val TELEGRAM_BAD_REQUEST_CODE = 400

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
                if (response?.ok == true) QuestionEditStatus.Updated else QuestionEditStatus.Failed
            },
            mapError = { error ->
                if (result.first?.code() == TELEGRAM_BAD_REQUEST_CODE) {
                    error.questionEditStatus()
                } else {
                    QuestionEditStatus.Failed
                }
            },
        )
    return when (editStatus) {
        QuestionEditStatus.Updated -> {
            messageSessionStorage.registerQuestion(telegramUserId, question, reply.callbackData())
            true
        }

        QuestionEditStatus.Missing -> {
            sendTrainingQuestion(question.sessionId, question.chatId, reply, messageSessionStorage, telegramUserId)
        }

        QuestionEditStatus.Failed -> {
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

private enum class QuestionEditStatus { Updated, Missing, Failed }

private fun ResponseError.questionEditStatus(): QuestionEditStatus =
    runCatching {
        val body = errorBody?.string() ?: return@runCatching QuestionEditStatus.Failed
        val error = Json.parseToJsonElement(body).jsonObject
        if (error["ok"]?.jsonPrimitive?.booleanOrNull != false ||
            error["error_code"]?.jsonPrimitive?.intOrNull != TELEGRAM_BAD_REQUEST_CODE
        ) {
            return@runCatching QuestionEditStatus.Failed
        }
        val description = error["description"]?.jsonPrimitive?.contentOrNull
        when {
            description == MESSAGE_NOT_MODIFIED || description?.startsWith("$MESSAGE_NOT_MODIFIED:") == true -> {
                QuestionEditStatus.Updated
            }

            description == MESSAGE_NOT_FOUND -> {
                QuestionEditStatus.Missing
            }

            else -> {
                QuestionEditStatus.Failed
            }
        }
    }.getOrDefault(QuestionEditStatus.Failed)
