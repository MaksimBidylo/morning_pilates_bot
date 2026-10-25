package morningpilates.telegram.logging

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.Update
import morningpilates.telegram.training.view.TrainingViewAction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

internal class TelegramUpdateObserver(
    private val logger: Logger = LoggerFactory.getLogger(TelegramUpdateObserver::class.java),
) : Handler {
    override fun checkUpdate(update: Update): Boolean = true

    override suspend fun handleUpdate(
        bot: Bot,
        update: Update,
    ) {
        val text = update.message?.text
        val kind =
            when {
                update.callbackQuery != null -> "callback"
                text?.startsWith("/") == true -> "command"
                text != null -> "text"
                else -> "other"
            }
        val operation =
            when (kind) {
                "command" -> commands[text?.drop(1)?.substringBefore(' ')?.substringBefore('@')] ?: "unknown"
                "callback" -> callbacks[update.callbackQuery?.data?.substringBeforeLast(':', "")] ?: "unknown"
                else -> "unknown"
            }
        logger.info(
            "event=telegram_received correlation_id={} kind={} operation={}",
            UUID.randomUUID(),
            kind,
            operation,
        )
    }

    private companion object {
        val commands =
            listOf(
                "start",
                "help",
                "cancel",
                "cancel_all",
                "profile",
                "training_add",
                "training_get_last",
                "training_get_date",
            ).associateWith { it }
        val callbacks =
            buildMap {
                put("training:cancel", "training_add")
                listOf(
                    "add_technique",
                    "add_technique_description",
                    "add_another_technique",
                    "add_note",
                    "confirmation",
                ).forEach { action ->
                    listOf("yes", "no").forEach { answer -> put("training:$action:$answer", "training_add") }
                }
                listOf("edit", "finish", "back", "clear").forEach { put("profile:$it", "profile") }
                TrainingViewAction.entries.forEach { put("view:${it.callbackName}", "training_view") }
            }
    }
}
