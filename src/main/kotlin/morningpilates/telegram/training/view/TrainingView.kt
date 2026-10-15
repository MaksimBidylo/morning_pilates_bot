package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.network.bimap
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageEditStatus
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.deleteMessageOrRemoveButtons
import morningpilates.telegram.api.messageEditStatus
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.training.SavedTraining
import morningpilates.user.TelegramUserId

internal fun Bot.showTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    training: Result<SavedTraining?>,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView?,
): Boolean {
    if (!viewStorage.isCurrent(userId, expected)) return false
    return if (expected == null) {
        sendTrainingView(userId, chatId, training, viewStorage, expected)
    } else {
        val actions = training.viewActions()
        val token = expected.token.takeIf { expected.actions == actions } ?: viewStorage.newToken()
        val reply = training.toTrainingViewReply(token, actions)
        when (updateTrainingView(expected, reply)) {
            TelegramMessageEditStatus.Updated -> {
                viewStorage.putIfCurrent(userId, expected, expected.copy(token = token, actions = actions))
            }

            TelegramMessageEditStatus.Missing -> {
                sendTrainingView(userId, chatId, training, viewStorage, expected)
            }

            TelegramMessageEditStatus.Failed -> {
                false
            }
        }
    }
}

private fun Bot.sendTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    training: Result<SavedTraining?>,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView?,
): Boolean {
    if (!viewStorage.isCurrent(userId, expected)) return false
    val token = viewStorage.newToken()
    val actions = training.viewActions()
    val reply = training.toTrainingViewReply(token, actions)
    val sent =
        sendMessage(
            chatId = ChatId.fromId(chatId.value),
            text = reply.text,
            parseMode = reply.parseMode,
            replyMarkup = reply.replyMarkup,
        ).getOrNull()
    return if (sent == null) {
        false
    } else {
        val registration =
            ActiveTrainingView(
                TelegramChatId(sent.chat.id),
                TelegramMessageId(sent.messageId),
                token,
                actions,
            )
        val registered = viewStorage.putIfCurrent(userId, expected, registration)
        if (!registered) deleteMessageOrRemoveButtons(registration.chatId, registration.messageId)
        registered
    }
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
    if (removed) deleteMessageOrRemoveButtons(active.chatId, active.messageId)
    return removed
}
