package morningpilates.telegram.training.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.profile.ProfileInteraction
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.showTrainingView
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

fun Dispatcher.registerTrainingGetLastCommand(
    queryService: TrainingQueryService,
    viewStorage: TrainingViewStorage,
    profile: ProfileInteraction? = null,
) {
    command("training_get_last") {
        if (message.chat.type != "private") return@command
        val sender = message.from ?: return@command
        val userId = TelegramUserId(sender.id)
        if (profile?.blockDiary(bot, userId, message.chat.id) == true) return@command
        val expected = viewStorage.get(userId)
        val training = queryService.findLatest(userId)
        if (bot.showTrainingView(userId, TelegramChatId(message.chat.id), training, viewStorage, expected)) {
            bot.deleteMessage(ChatId.fromId(message.chat.id), messageId = message.messageId)
        }
    }
}
