package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.network.bimap
import morningpilates.training.SavedTraining
import morningpilates.user.TelegramUserId

internal fun Bot.showTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    training: Result<SavedTraining?>,
    viewStorage: TrainingViewStorage,
): Boolean {
    val active = viewStorage.get(userId) ?: return sendTrainingView(userId, chatId, training, viewStorage)
    val actions = training.viewActions()
    val token = active.token.takeIf { active.actions == actions } ?: viewStorage.newToken()
    val reply = training.toTrainingViewReply(token, actions)
    return when (updateTrainingView(active, reply)) {
        TelegramMessageEditStatus.Updated -> {
            viewStorage.put(userId, active.copy(token = token, actions = actions))
            true
        }

        TelegramMessageEditStatus.Missing -> {
            sendTrainingView(userId, chatId, training, viewStorage)
        }

        TelegramMessageEditStatus.Failed -> {
            false
        }
    }
}

private fun Bot.sendTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    training: Result<SavedTraining?>,
    viewStorage: TrainingViewStorage,
): Boolean {
    val token = viewStorage.newToken()
    val actions = training.viewActions()
    val reply = training.toTrainingViewReply(token, actions)
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
            token,
            actions,
        ),
    )
    return true
}

private fun Bot.updateTrainingView(
    view: ActiveTrainingView,
    reply: TrainingReply,
): TelegramMessageEditStatus {
    val result =
        editMessageText(
            chatId = ChatId.fromId(view.chatId.value),
            messageId = view.messageId.value,
            text = reply.text,
            parseMode = reply.parseMode,
            replyMarkup = reply.replyMarkup,
        )
    return result.bimap(
        mapResponse = {
            if (it?.ok == true) TelegramMessageEditStatus.Updated else TelegramMessageEditStatus.Failed
        },
        mapError = { error -> error.messageEditStatus(result.first?.code()) },
    )
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
