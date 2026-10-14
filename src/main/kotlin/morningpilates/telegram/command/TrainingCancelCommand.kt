package morningpilates.telegram.command

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.TrainingMessageSession
import morningpilates.telegram.TrainingMessageSessionStorage
import morningpilates.user.TelegramUserId

private const val NO_ACTIVE_TRAINING_MESSAGE = "Нет активной тренировки."
private const val TRAINING_CANCELLED_MESSAGE = "Создание тренировки отменено."

fun Dispatcher.registerTrainingCancelCommand(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    command("cancel") {
        if (message.chat.type != "private") return@command
        val sender = message.from ?: return@command
        val userId = TelegramUserId(sender.id)
        val chatId = ChatId.fromId(message.chat.id)
        if (storage.get(userId) == null) {
            bot.sendMessage(chatId, text = NO_ACTIVE_TRAINING_MESSAGE).getOrNull() ?: return@command
            bot.deleteMessage(chatId, messageId = message.messageId)
            return@command
        }

        val question = messageSessionStorage.get(userId)
        val result = sessionManager.handle(FlowEvent.Cancel(userId))
        if (result == FlowResult.Cancelled) {
            val displayed =
                if (question == null) {
                    bot.sendMessage(chatId, text = TRAINING_CANCELLED_MESSAGE).getOrNull() != null
                } else {
                    bot.showTrainingCancellation(userId, question, messageSessionStorage)
                }
            if (displayed) {
                bot.deleteMessage(chatId, messageId = message.messageId)
            }
            return@command
        }
        val reply = (result as? FlowResult.Rejected)?.reason ?: return@command
        if (question == null) return@command
        val edited =
            bot.editMessageText(
                chatId = ChatId.fromId(question.chatId.value),
                messageId = question.messageId.value,
                text = reply,
                replyMarkup = InlineKeyboardMarkup.create(),
            )
        if (edited.first?.body()?.ok != true) return@command
        bot.deleteMessage(chatId, messageId = message.messageId)
    }
}

internal fun Bot.showTrainingCancellation(
    userId: TelegramUserId,
    question: TrainingMessageSession,
    messageSessionStorage: TrainingMessageSessionStorage,
): Boolean {
    val edited =
        editMessageText(
            chatId = ChatId.fromId(question.chatId.value),
            messageId = question.messageId.value,
            text = TRAINING_CANCELLED_MESSAGE,
            replyMarkup = InlineKeyboardMarkup.create(),
        )
    if (edited.first?.body()?.ok != true) return false
    messageSessionStorage.remove(userId, question)
    return true
}
