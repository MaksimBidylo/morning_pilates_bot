package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.network.bimap
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageEditStatus
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.deleteMessageOrRemoveButtons
import morningpilates.telegram.api.messageEditStatus
import morningpilates.telegram.training.calendar.TrainingDateCalendar
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.training.SavedTraining
import morningpilates.user.TelegramUserId
import java.time.YearMonth

internal fun Bot.showTrainingDateView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView?,
    search: TrainingDateSearch = TrainingDateSearch(),
    newMessage: Boolean = false,
): Boolean {
    val dateSearch =
        if (search.isWaitingForDate && search.calendar == null && viewStorage.dateCalendarEnabled) {
            search.copy(calendar = TrainingDateCalendar(YearMonth.now(viewStorage.clock)))
        } else {
            search
        }
    val content = TrainingViewContent(dateSearch = dateSearch)
    return if (newMessage) {
        sendTrainingView(userId, chatId, viewStorage, expected, content)
    } else {
        showTrainingViewContent(userId, chatId, viewStorage, expected, content)
    }
}

internal fun Bot.showTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    training: Result<SavedTraining?>,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView?,
    dateSearch: TrainingDateSearch? = null,
): Boolean = showTrainingViewContent(userId, chatId, viewStorage, expected, TrainingViewContent(training, dateSearch))

internal fun Bot.showTrainingViewContent(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView?,
    content: TrainingViewContent,
): Boolean {
    if (!viewStorage.isCurrent(userId, expected)) return false
    return if (expected == null) {
        sendTrainingView(userId, chatId, viewStorage, expected, content)
    } else {
        val actions = content.actions()
        val token =
            expected.token.takeIf {
                expected.actions == actions && expected.dateSearch == null && content.dateSearch == null &&
                    expected.deletionTarget == null && content.deletionTarget == null
            }
                ?: viewStorage.newToken()
        val reply = content.reply(token, actions)
        when (updateTrainingView(expected, reply)) {
            TelegramMessageEditStatus.Updated -> {
                viewStorage.putIfCurrent(
                    userId,
                    expected,
                    expected.withContent(content, token, actions),
                )
            }

            TelegramMessageEditStatus.Missing -> {
                sendTrainingView(userId, chatId, viewStorage, expected, content)
            }

            TelegramMessageEditStatus.Failed -> {
                false
            }
        }
    }
}

internal fun Bot.showCommittedTrainingViewContent(
    userId: TelegramUserId,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView,
    content: TrainingViewContent,
): Boolean {
    val committed = expected.withContent(content, viewStorage.newToken(), emptySet())
    return viewStorage.putIfCurrent(userId, expected, committed) &&
        showTrainingViewContent(userId, committed.chatId, viewStorage, committed, content)
}

private fun ActiveTrainingView.withContent(
    content: TrainingViewContent,
    token: TrainingViewToken,
    actions: Set<TrainingViewAction>,
): ActiveTrainingView =
    copy(
        token = token,
        actions = actions,
        dateSearch = content.dateSearch,
        latestTraining = content.training.getOrNull(),
        deletionTarget = content.deletionTarget,
        deletionOutcome = content.deletionOutcome,
    )

private fun Bot.sendTrainingView(
    userId: TelegramUserId,
    chatId: TelegramChatId,
    viewStorage: TrainingViewStorage,
    expected: ActiveTrainingView?,
    content: TrainingViewContent,
): Boolean {
    if (!viewStorage.isCurrent(userId, expected)) return false
    val token = viewStorage.newToken()
    val actions = content.actions()
    val reply = content.reply(token, actions)
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
                content.dateSearch,
                content.training.getOrNull(),
                content.deletionTarget,
                content.deletionOutcome,
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
