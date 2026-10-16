package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.api.deleteMessageOrRemoveButtons
import morningpilates.telegram.profile.ProfileInteraction
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.closeTrainingView
import morningpilates.user.TelegramUserId

private const val ALL_ACTIONS_CANCELLED_MESSAGE =
    "Все незавершённые действия отменены.\n\nЧтобы записать тренировку, используйте /training_add."

fun Dispatcher.registerCancelAllCommand(
    storage: FlowStorage,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    viewStorage: TrainingViewStorage,
    profile: ProfileInteraction? = null,
) {
    command("cancel_all") {
        if (message.chat.type != "private") return@command
        val sender = message.from ?: return@command
        val userId = TelegramUserId(sender.id)
        profile?.close(bot, userId)
        val session = storage.get(userId)
        val stored = messageSessionStorage.snapshot(userId)
        if (session != null) storage.remove(userId, session)
        val question =
            if (stored != null && messageSessionStorage.remove(userId, stored.registration)) {
                stored.registration ?: stored.question?.message
            } else {
                null
            }

        bot.closeTrainingView(userId, viewStorage)
        if (question != null) bot.deleteMessageOrRemoveButtons(question.chatId, question.messageId)

        val chatId = ChatId.fromId(message.chat.id)
        bot.sendMessage(chatId, text = ALL_ACTIONS_CANCELLED_MESSAGE).getOrNull() ?: return@command
        bot.deleteMessage(chatId, messageId = message.messageId)
    }
}
