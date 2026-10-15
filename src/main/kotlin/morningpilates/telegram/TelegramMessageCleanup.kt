package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

internal fun Bot.deleteMessageOrRemoveButtons(
    chatId: TelegramChatId,
    messageId: TelegramMessageId,
) {
    val telegramChatId = ChatId.fromId(chatId.value)
    if (deleteMessage(telegramChatId, messageId.value).getOrNull() != true) {
        editMessageReplyMarkup(
            chatId = telegramChatId,
            messageId = messageId.value,
            replyMarkup = InlineKeyboardMarkup.create(),
        )
    }
}
