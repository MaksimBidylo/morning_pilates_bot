package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandler
import com.github.kotlintelegrambot.dispatcher.handlers.HandleCallbackQuery
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.Update

private const val STALE_DIALOG_NOTICE = "Этот диалог больше неактуален. Используйте меню команд."

internal fun Dispatcher.registerAcknowledgedCallback(
    namespace: String,
    isFromPreviousLaunch: (String) -> Boolean,
    handleCallback: HandleCallbackQuery,
) {
    val current = CallbackQueryHandler(handleCallbackQuery = handleCallback)
    val previous = CallbackQueryHandler(callbackAnswerText = STALE_DIALOG_NOTICE, handleCallbackQuery = {})
    addHandler(
        object : Handler by current {
            override fun checkUpdate(update: Update): Boolean {
                val data = update.callbackQuery?.data
                return data?.startsWith(namespace) == true
            }

            override suspend fun handleUpdate(
                bot: Bot,
                update: Update,
            ) {
                val callback = checkNotNull(update.callbackQuery)
                val selected = if (isFromPreviousLaunch(callback.data)) previous else current
                selected.handleUpdate(bot, update)
            }
        },
    )
}
