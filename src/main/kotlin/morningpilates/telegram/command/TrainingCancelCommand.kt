package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
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

        val question = messageSessionStorage.get(userId) ?: return@command
        val result = sessionManager.handle(FlowEvent.Cancel(userId))
        val reply =
            when (result) {
                FlowResult.Cancelled -> TRAINING_CANCELLED_MESSAGE
                is FlowResult.Rejected -> result.reason
                else -> return@command
            }
        val edited =
            bot.editMessageText(
                chatId = ChatId.fromId(question.chatId.value),
                messageId = question.messageId.value,
                text = reply,
                replyMarkup = InlineKeyboardMarkup.create(),
            )
        if (edited.first?.body()?.ok != true) return@command
        if (result == FlowResult.Cancelled) {
            messageSessionStorage.remove(userId, question)
        }
        bot.deleteMessage(chatId, messageId = message.messageId)
    }
}
