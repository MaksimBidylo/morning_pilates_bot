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
import morningpilates.telegram.promptFor
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import java.time.Instant

private const val UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."

fun Dispatcher.registerTrainingAddCommand(
    storage: FlowStorage,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    command("training_add") {
        if (message.chat.type != "private") return@command

        val sender = message.from ?: return@command
        val telegramUserId = TelegramUserId(sender.id)
        val chatId = ChatId.fromId(message.chat.id)
        val session =
            UserSession(
                state = WaitingDate,
                createdAt = Instant.now(),
            )
        if (!storage.start(telegramUserId, session)) {
            bot.sendMessage(chatId = chatId, text = UNFINISHED_TRAINING_MESSAGE)
            return@command
        }

        val sentMessage =
            bot
                .sendMessage(
                    chatId = chatId,
                    text = checkNotNull(promptFor(WaitingDate)).text,
                ).getOrNull() ?: return@command

        messageSessionStorage.put(
            telegramUserId,
            TrainingMessageSession(
                sessionId = session.sessionId,
                chatId = TelegramChatId(sentMessage.chat.id),
                messageId = TelegramMessageId(sentMessage.messageId),
            ),
        )
        bot.deleteMessage(
            chatId = chatId,
            messageId = message.messageId,
        )
    }
}
