package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.telegram.TelegramChatId
import morningpilates.telegram.TrainingViewStorage
import morningpilates.telegram.showTrainingView
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

fun Dispatcher.registerTrainingGetLastCommand(
    queryService: TrainingQueryService,
    viewStorage: TrainingViewStorage,
) {
    command("training_get_last") {
        if (message.chat.type != "private") return@command
        val sender = message.from ?: return@command
        val userId = TelegramUserId(sender.id)
        val training = queryService.findLatest(userId)
        if (bot.showTrainingView(userId, TelegramChatId(message.chat.id), training, viewStorage)) {
            bot.deleteMessage(ChatId.fromId(message.chat.id), messageId = message.messageId)
        }
    }
}
