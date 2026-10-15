package morningpilates.telegram.training.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.telegram.training.creation.promptForCreation
import morningpilates.telegram.training.creation.sendTrainingCreationQuestion
import morningpilates.telegram.training.creation.withCreationQuestionToken
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.closeTrainingView
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import java.time.Instant

private const val UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."
private const val UNFINISHED_TRAINING_WARNING =
    "⚠️ <b>У вас есть незавершённая тренировка</b>\n\n" +
        "Продолжите или отмените её, прежде чем создавать новую."

fun Dispatcher.registerTrainingAddCommand(
    storage: FlowStorage,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    viewStorage: TrainingViewStorage,
) {
    command("training_add") {
        if (message.chat.type != "private") return@command

        val sender = message.from ?: return@command
        val telegramUserId = TelegramUserId(sender.id)
        bot.closeTrainingView(telegramUserId, viewStorage)
        val chatId = ChatId.fromId(message.chat.id)
        val activeSession = storage.get(telegramUserId)
        val showUnfinishedWarning =
            activeSession != null &&
                messageSessionStorage.get(telegramUserId) != null &&
                messageSessionStorage.questionToken(telegramUserId) != null
        val session = activeSession ?: storage.startDateSession(telegramUserId)
        if (session == null) {
            bot.sendMessage(chatId = chatId, text = UNFINISHED_TRAINING_MESSAGE)
            return@command
        }

        val reply =
            (promptForCreation(session.state) ?: return@command)
                .withCreationQuestionToken(messageSessionStorage.newQuestionToken())
        val displayedReply = if (showUnfinishedWarning) reply.withUnfinishedWarning() else reply
        if (!bot.sendTrainingCreationQuestion(
                session,
                TelegramChatId(message.chat.id),
                displayedReply,
                messageSessionStorage,
                telegramUserId,
                storage,
            )
        ) {
            return@command
        }
        bot.deleteMessage(
            chatId = chatId,
            messageId = message.messageId,
        )
    }
}

private fun FlowStorage.startDateSession(userId: TelegramUserId): UserSession? {
    val session = UserSession(state = WaitingDate, createdAt = Instant.now())
    return if (start(userId, session)) session else null
}

private fun TrainingReply.withUnfinishedWarning(): TrainingReply {
    val questionText = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return copy(text = "$UNFINISHED_TRAINING_WARNING\n\n$questionText", parseMode = ParseMode.HTML)
}
