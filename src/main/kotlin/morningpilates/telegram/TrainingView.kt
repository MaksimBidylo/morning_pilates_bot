package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.network.bimap
import morningpilates.training.TrainingDetails
import morningpilates.user.TelegramUserId
import java.util.UUID

private const val FINISH_VIEW_CALLBACK = "view:finish"
private const val FINISH_VIEW_TEXT = "Завершить просмотр"
private val EMPTY_VIEW_REPLY =
    TrainingReply(
        "Тренировок пока нет.\n\nЗапиши свою первую тренировку с помощью команды /training_add.",
        replyMarkup = InlineKeyboardMarkup.create(),
    )

internal fun Bot.showTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    details: TrainingDetails?,
    viewStorage: TrainingViewStorage,
): Boolean {
    val active = viewStorage.get(userId)
    val token = active?.token ?: TrainingViewToken(UUID.randomUUID())
    val reply = details?.toTrainingViewReply(token) ?: EMPTY_VIEW_REPLY
    val displayedToken = token.takeIf { details != null }
    return if (active == null) {
        val sent =
            sendMessage(
                chatId = ChatId.fromId(chatId.value),
                text = reply.text,
                parseMode = reply.parseMode,
                replyMarkup = reply.replyMarkup,
            ).getOrNull() ?: return false
        viewStorage.put(
            userId,
            ActiveTrainingView(
                TelegramChatId(sent.chat.id),
                TelegramMessageId(sent.messageId),
                displayedToken,
            ),
        )
        true
    } else {
        val displayed = updateTrainingView(active, reply)
        if (displayed) viewStorage.put(userId, active.copy(token = displayedToken))
        displayed
    }
}

private fun Bot.updateTrainingView(
    view: ActiveTrainingView,
    reply: TrainingReply,
): Boolean {
    val result =
        editMessageText(
            chatId = ChatId.fromId(view.chatId.value),
            messageId = view.messageId.value,
            text = reply.text,
            parseMode = reply.parseMode,
            replyMarkup = reply.replyMarkup,
        )
    val status =
        result.bimap(
            mapResponse = {
                if (it?.ok == true) TelegramMessageEditStatus.Updated else TelegramMessageEditStatus.Failed
            },
            mapError = { error -> error.messageEditStatus(result.first?.code()) },
        )
    return status == TelegramMessageEditStatus.Updated
}

internal fun Bot.closeTrainingView(
    userId: TelegramUserId,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView? = viewStorage.get(userId),
): Boolean {
    val active = expected ?: return false
    val removed = viewStorage.remove(userId, active)
    if (removed) deleteTrainingViewMessage(active)
    return removed
}

private fun Bot.deleteTrainingViewMessage(view: ActiveTrainingView) {
    val chatId = ChatId.fromId(view.chatId.value)
    val deleted = deleteMessage(chatId, view.messageId.value)
    if (deleted.getOrNull() != true) {
        editMessageReplyMarkup(
            chatId = chatId,
            messageId = view.messageId.value,
            replyMarkup = InlineKeyboardMarkup.create(),
        )
    }
}

internal fun Dispatcher.registerTrainingViewCallbacks(viewStorage: TrainingViewStorage) {
    callbackQuery(FINISH_VIEW_CALLBACK) {
        val userId = TelegramUserId(callbackQuery.from.id)
        val active = viewStorage.get(userId) ?: return@callbackQuery
        val message = callbackQuery.message ?: return@callbackQuery
        if (message.chat.type != "private") return@callbackQuery
        if (TelegramChatId(message.chat.id) == active.chatId &&
            TelegramMessageId(message.messageId) == active.messageId &&
            callbackQuery.data == active.token?.finishCallbackData()
        ) {
            bot.closeTrainingView(userId, viewStorage, active)
        }
    }
}

private fun TrainingDetails.toTrainingViewReply(token: TrainingViewToken): TrainingReply =
    toSavedTrainingReply(title = "Последняя тренировка").copy(
        replyMarkup =
            InlineKeyboardMarkup.create(
                listOf(InlineKeyboardButton.CallbackData(FINISH_VIEW_TEXT, token.finishCallbackData())),
            ),
    )

private fun TrainingViewToken.finishCallbackData(): String = "$FINISH_VIEW_CALLBACK:$value"
