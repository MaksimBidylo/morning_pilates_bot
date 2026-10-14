package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import morningpilates.user.TelegramUserId

internal val NO_ACTIVE_ACTION_MENU =
    """
    Сейчас нет активного действия.

    Выберите, что хотите сделать:

    /training_add — записать тренировку
    """.trimIndent()

internal fun Bot.handlePreviousLaunchCallback(
    callback: CallbackQuery,
    messageSessionStorage: TrainingMessageSessionStorage,
    hasActiveSession: Boolean,
): Boolean {
    if (!messageSessionStorage.isFromPreviousLaunch(callback.data)) return false
    val message = checkNotNull(callback.message)
    val current = messageSessionStorage.get(TelegramUserId(callback.from.id))
    val chatId = TelegramChatId(message.chat.id)
    val messageId = TelegramMessageId(message.messageId)
    if (current?.chatId != chatId || current.messageId != messageId) {
        editMessageText(
            chatId = ChatId.fromId(chatId.value),
            messageId = messageId.value,
            text = staleDialogueText(hasActiveSession),
            replyMarkup = InlineKeyboardMarkup.create(),
        )
    }
    return true
}

private fun staleDialogueText(hasActiveSession: Boolean): String {
    val nextAction =
        if (hasActiveSession) "Продолжите работу в актуальном диалоге." else NO_ACTIVE_ACTION_MENU
    return "⚠️ Это сообщение больше неактуально.\n\n$nextAction"
}
