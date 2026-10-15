package morningpilates.telegram.training.view

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

            TrainingViewAction.Retry -> {
                bot.showTrainingView(userId, active.chatId, queryService.findLatest(userId), viewStorage, active)
            }
        }
    }
