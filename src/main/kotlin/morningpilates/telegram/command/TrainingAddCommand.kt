package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import java.time.Instant

private val DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()

fun Dispatcher.registerTrainingAddCommand(storage: FlowStorage) {
    command("training_add") {
        val sender = message.from ?: return@command
        val telegramUserId = TelegramUserId(sender.id)
        val session =
            UserSession(
                state = WaitingDate,
                createdAt = Instant.now(),
            )
        if (!storage.start(telegramUserId, session)) {
            return@command
        }

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = DATE_PROMPT,
        )
    }
}
