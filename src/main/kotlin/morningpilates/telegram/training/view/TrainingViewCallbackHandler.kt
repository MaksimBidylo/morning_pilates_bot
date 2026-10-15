package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.HandleCallbackQuery
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.registerAcknowledgedCallback
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

internal fun Dispatcher.registerTrainingViewCallbacks(
    viewStorage: TrainingViewStorage,
    queryService: TrainingQueryService,
) {
    registerAcknowledgedCallback(
        namespace = "view:",
        isFromPreviousLaunch = viewStorage::isFromPreviousLaunch,
        handleCallback = viewCallback(viewStorage, queryService),
    )
}

private fun viewCallback(
    viewStorage: TrainingViewStorage,
    queryService: TrainingQueryService,
): HandleCallbackQuery =
    handle@{
        val userId = TelegramUserId(callbackQuery.from.id)
        val active = viewStorage.get(userId) ?: return@handle
        val message = callbackQuery.message ?: return@handle
        if (message.chat.type != "private" ||
            TelegramChatId(message.chat.id) != active.chatId ||
            TelegramMessageId(message.messageId) != active.messageId
        ) {
            return@handle
        }
        val action = active.actions.firstOrNull { active.token.callbackData(it) == callbackQuery.data } ?: return@handle
        when (action) {
            TrainingViewAction.Finish -> {
                bot.closeTrainingView(userId, viewStorage, active)
            }

            TrainingViewAction.ChangeDate -> {
                bot.showTrainingDateView(userId, active.chatId, viewStorage, active)
            }

            TrainingViewAction.Previous, TrainingViewAction.Next -> {
                val search = active.dateSearch ?: return@handle
                val index = search.index + if (action == TrainingViewAction.Next) 1 else -1
                if (index !in search.trainings.indices) return@handle
                bot.showTrainingDateView(userId, active.chatId, viewStorage, active, search.copy(index = index))
            }

            TrainingViewAction.Retry -> {
                bot.retryTrainingView(userId, active, viewStorage, queryService)
            }
        }
    }

private fun Bot.retryTrainingView(
    userId: TelegramUserId,
    active: ActiveTrainingView,
    viewStorage: TrainingViewStorage,
    queryService: TrainingQueryService,
) {
    val date = active.dateSearch?.date
    if (date == null) {
        showTrainingView(userId, active.chatId, queryService.findLatest(userId), viewStorage, active)
    } else {
        showTrainingDateView(
            userId,
            active.chatId,
            viewStorage,
            active,
            queryService.loadDateSearch(userId, date),
        )
    }
}
