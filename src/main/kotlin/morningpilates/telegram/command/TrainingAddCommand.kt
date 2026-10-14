package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.TelegramChatId
import morningpilates.telegram.TrainingMessageSessionStorage
import morningpilates.telegram.TrainingReply
import morningpilates.telegram.TrainingViewStorage
import morningpilates.telegram.closeTrainingView
import morningpilates.telegram.promptFor
import morningpilates.telegram.sendTrainingQuestion
import morningpilates.telegram.updateTrainingQuestionAndDeleteInput
import morningpilates.telegram.withQuestionToken
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
    messageSessionStorage: TrainingMessageSessionStorage,
    viewStorage: TrainingViewStorage,
) {
    command("training_add") {
        if (message.chat.type != "private") return@command

        val sender = message.from ?: return@command
        val telegramUserId = TelegramUserId(sender.id)
        bot.closeTrainingView(telegramUserId, viewStorage)
        val chatId = ChatId.fromId(message.chat.id)
        val activeSession = storage.get(telegramUserId)
        val registeredQuestion = messageSessionStorage.get(telegramUserId)
        if (activeSession != null && registeredQuestion != null) {
            val prompt = promptFor(activeSession.state) ?: return@command
            val token = messageSessionStorage.questionToken(telegramUserId)
            val reply = prompt.withQuestionToken(token ?: messageSessionStorage.newQuestionToken())
            val displayedReply = if (token == null) reply else reply.withUnfinishedWarning()
            bot.updateTrainingQuestionAndDeleteInput(
                registeredQuestion,
                displayedReply,
                message,
                messageSessionStorage,
                telegramUserId,
            )
            return@command
        }
        val session = activeSession ?: storage.startDateSession(telegramUserId)
        if (session == null) {
            bot.sendMessage(chatId = chatId, text = UNFINISHED_TRAINING_MESSAGE)
            return@command
        }

        val reply =
            (promptFor(session.state) ?: return@command)
                .withQuestionToken(messageSessionStorage.newQuestionToken())
        if (!bot.sendTrainingQuestion(
                session.sessionId,
                TelegramChatId(message.chat.id),
                reply,
                messageSessionStorage,
                telegramUserId,
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
