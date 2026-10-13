package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.TelegramChatId
import morningpilates.telegram.TelegramMessageId
import morningpilates.telegram.TrainingMessageSession
import morningpilates.telegram.TrainingMessageSessionStorage
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import java.time.Instant

private const val UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."

private val DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()

fun Dispatcher.registerTrainingAddCommand(
    storage: FlowStorage,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    command("training_add") {
        if (message.chat.type != "private") return@command

        val sender = message.from ?: return@command
        val telegramUserId = TelegramUserId(sender.id)
        val session =
            UserSession(
                state = WaitingDate,
                createdAt = Instant.now(),
            )
        val sessionStarted = storage.start(telegramUserId, session)
        val replyText =
            if (sessionStarted) {
                DATE_PROMPT
            } else {
                UNFINISHED_TRAINING_MESSAGE
            }

        val sentMessage =
            bot
                .sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = replyText,
                ).getOrNull()
        if (!sessionStarted || sentMessage == null) return@command

        messageSessionStorage.put(
            telegramUserId,
            TrainingMessageSession(
                sessionId = session.sessionId,
                chatId = TelegramChatId(sentMessage.chat.id),
                messageId = TelegramMessageId(sentMessage.messageId),
            ),
        )
        bot.deleteMessage(
            chatId = ChatId.fromId(message.chat.id),
            messageId = message.messageId,
        )
    }
}
