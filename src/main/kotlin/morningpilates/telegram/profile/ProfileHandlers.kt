package morningpilates.telegram.profile

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.telegram.api.registerAcknowledgedCallback
import morningpilates.user.TelegramUserId

internal fun Dispatcher.registerProfileHandlers(profile: ProfileInteraction) {
    command("profile") {
        if (message.chat.type != "private") return@command
        val userId = TelegramUserId(message.from?.id ?: return@command)
        if (profile.open(bot, userId, message.chat.id)) {
            bot.deleteMessage(ChatId.fromId(message.chat.id), message.messageId)
        }
    }
    text {
        if (message.chat.type != "private" || text.startsWith("/")) return@text
        val userId = TelegramUserId(message.from?.id ?: return@text)
        profile.text(bot, userId, message.chat.id, text, message.messageId)
    }
    registerAcknowledgedCallback("profile:", profile::isFromPreviousLaunch) {
        val message = callbackQuery.message ?: return@registerAcknowledgedCallback
        if (message.chat.type != "private") return@registerAcknowledgedCallback
        profile.callback(
            bot,
            TelegramUserId(callbackQuery.from.id),
            message.chat.id,
            message.messageId,
            callbackQuery.data,
        )
    }
}
