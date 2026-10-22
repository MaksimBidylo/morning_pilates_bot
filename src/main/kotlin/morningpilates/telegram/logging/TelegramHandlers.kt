package morningpilates.telegram.logging

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.handlers.CommandHandler
import com.github.kotlintelegrambot.dispatcher.handlers.HandleCommand
import com.github.kotlintelegrambot.dispatcher.handlers.HandleText
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.dispatcher.handlers.TextHandler
import com.github.kotlintelegrambot.entities.Update
import kotlinx.coroutines.CancellationException
import morningpilates.diagnostics.RequestDiagnostics

/** Collects SDK handlers into one public Handler boundary; no SDK internals or reflection. */
class TelegramHandlers {
    private val handlers = linkedSetOf<Handler>()

    fun addHandler(handler: Handler) {
        handlers.add(handler)
    }

    fun command(
        command: String,
        handleCommand: HandleCommand,
    ) {
        addHandler(CommandHandler(command, handleCommand))
    }

    fun text(handleText: HandleText) {
        addHandler(TextHandler(handleText = handleText))
    }

    fun build(): Handler {
        val registered = handlers.toList()
        return object : Handler {
            override fun checkUpdate(update: Update): Boolean = true

            @Suppress("TooGenericExceptionCaught")
            override suspend fun handleUpdate(
                bot: Bot,
                update: Update,
            ) {
                RequestDiagnostics.process(update.kind(), update.route()) {
                    registered.forEach { handler ->
                        if (!update.consumed) {
                            try {
                                if (handler.checkUpdate(update)) handler.handleUpdate(bot, update)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                // Preserve SDK's continue-after-handler-error behavior, but make failure visible.
                                RequestDiagnostics.failure(failure)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Update.kind(): String =
    when {
        callbackQuery != null -> "callback"
        message?.text?.startsWith("/") == true -> "command"
        message?.text != null -> "text"
        else -> "other"
    }

// Return only constants from allowlists, never arbitrary command names or callback fields.
private val commandRoutes =
    mapOf(
        "/start" to "start",
        "/help" to "help",
        "/training_add" to "training_add",
        "/training_get_last" to "training_get_last",
        "/training_get_date" to "training_get_date",
        "/cancel" to "cancel",
        "/cancel_all" to "cancel_all",
        "/profile" to "profile",
    )
private val callbackRoutes =
    mapOf(
        "training:" to "training",
        "training-calendar:" to "training_calendar",
        "training-time:" to "training_time",
        "profile:" to "profile",
        "view:" to "view",
        "date:" to "date_search",
    )

private fun Update.route(): String {
    val callback = callbackQuery?.data
    if (callback != null) {
        return callbackRoutes.entries.firstOrNull { callback.startsWith(it.key) }?.value ?: "unknown"
    }
    val command = message?.text?.substringBefore(' ')?.substringBefore('@')
    return commandRoutes[command] ?: "unknown"
}
