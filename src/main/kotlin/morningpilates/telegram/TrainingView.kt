package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.training.TrainingDetails
import morningpilates.user.TelegramUserId
import java.util.UUID

private const val FINISH_VIEW_CALLBACK = "view:finish"
private const val FINISH_VIEW_TEXT = "Завершить просмотр"

internal fun Bot.showTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    details: TrainingDetails,
    viewStorage: TrainingViewStorage,
) {
    val active = viewStorage.get(userId)
    val token = active?.token ?: TrainingViewToken(UUID.randomUUID())
    val reply =
        details.toSavedTrainingReply(title = "Последняя тренировка").copy(
            replyMarkup =
                InlineKeyboardMarkup.create(
                    listOf(
                        InlineKeyboardButton.CallbackData(FINISH_VIEW_TEXT, token.finishCallbackData()),
                    ),
                ),
        )
    if (active == null) {
        val sent =
            sendMessage(
                chatId = ChatId.fromId(chatId.value),
                text = reply.text,
                parseMode = reply.parseMode,
                replyMarkup = reply.replyMarkup,
            ).getOrNull() ?: return
        viewStorage.put(
            userId,
            ActiveTrainingView(
                TelegramChatId(sent.chat.id),
                TelegramMessageId(sent.messageId),
                token,
            ),
        )
    } else {
        editMessageText(
            chatId = ChatId.fromId(active.chatId.value),
            messageId = active.messageId.value,
            text = reply.text,
            parseMode = reply.parseMode,
            replyMarkup = reply.replyMarkup,
        )
    }
}

internal fun Bot.closeTrainingView(
    userId: TelegramUserId,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView? = viewStorage.get(userId),
): Boolean {
    val active = expected ?: return false
    val removed = viewStorage.remove(userId, active)
    if (removed) {
        val deleted = deleteMessage(ChatId.fromId(active.chatId.value), active.messageId.value)
        if (deleted.getOrNull() != true) {
            editMessageReplyMarkup(
                chatId = ChatId.fromId(active.chatId.value),
                messageId = active.messageId.value,
                replyMarkup = InlineKeyboardMarkup.create(),
            )
        }
    }
    return removed
}

internal fun Dispatcher.registerTrainingViewCallbacks(viewStorage: TrainingViewStorage) {
    callbackQuery(FINISH_VIEW_CALLBACK) {
        val userId = TelegramUserId(callbackQuery.from.id)
        val active = viewStorage.get(userId) ?: return@callbackQuery
        val message = callbackQuery.message ?: return@callbackQuery
        if (message.chat.type != "private") return@callbackQuery
        if (TelegramChatId(message.chat.id) == active.chatId &&
            TelegramMessageId(message.messageId) == active.messageId &&
            callbackQuery.data == active.token.finishCallbackData()
        ) {
            bot.closeTrainingView(userId, viewStorage, active)
        }
    }
}

private fun TrainingViewToken.finishCallbackData(): String = "$FINISH_VIEW_CALLBACK:$value"
