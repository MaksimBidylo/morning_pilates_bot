package morningpilates.telegram.training.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.telegram.training.creation.promptForCreation
import morningpilates.telegram.training.creation.updateTrainingCreationQuestion
import morningpilates.telegram.training.creation.withCreationQuestionToken
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.showTrainingDateView
import morningpilates.user.TelegramUserId

private const val UNFINISHED_SEARCH_WARNING =
    "У вас есть незавершённая тренировка. Продолжите или отмените её, прежде чем начать поиск."

fun Dispatcher.registerTrainingGetDateCommand(
    storage: FlowStorage,
    messages: TrainingCreationMessageSessionStorage,
    views: TrainingViewStorage,
) {
    command("training_get_date") {
        if (message.chat.type != "private") return@command
        val userId = TelegramUserId(message.from?.id ?: return@command)
        val session = storage.get(userId)
        if (session != null) {
            val prompt = promptForCreation(session.state) ?: return@command
            val token = messages.questionToken(userId) ?: return@command
            val reply = prompt.withCreationQuestionToken(token)
            if (bot.updateTrainingCreationQuestion(
                    session,
                    reply.copy(text = "$UNFINISHED_SEARCH_WARNING\n\n${reply.text}"),
                    messages,
                    userId,
                    storage,
                )
            ) {
                bot.deleteMessage(ChatId.fromId(message.chat.id), message.messageId)
            }
            return@command
        }
        if (bot.showTrainingDateView(userId, TelegramChatId(message.chat.id), views, views.get(userId))) {
            bot.deleteMessage(ChatId.fromId(message.chat.id), message.messageId)
        }
    }
}
