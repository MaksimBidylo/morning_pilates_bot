package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.TelegramChatId
import morningpilates.telegram.TrainingMessageSessionStorage
import morningpilates.telegram.cancelActiveFlow
import morningpilates.user.TelegramUserId

private const val NO_ACTIVE_ACTION_MESSAGE = "Нет активного действия."

fun Dispatcher.registerCancelCommand(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    command("cancel") {
        if (message.chat.type != "private") return@command
        val sender = message.from ?: return@command
        val userId = TelegramUserId(sender.id)
        val chatId = ChatId.fromId(message.chat.id)
        val session = storage.get(userId)
        if (session == null) {
            bot.sendMessage(chatId, text = NO_ACTIVE_ACTION_MESSAGE).getOrNull() ?: return@command
            bot.deleteMessage(chatId, messageId = message.messageId)
            return@command
        }

        val displayed =
            bot.cancelActiveFlow(
                userId,
                session,
                TelegramChatId(message.chat.id),
                sessionManager,
                messageSessionStorage,
            )
        if (displayed) {
            bot.deleteMessage(chatId, messageId = message.messageId)
        }
    }
}
