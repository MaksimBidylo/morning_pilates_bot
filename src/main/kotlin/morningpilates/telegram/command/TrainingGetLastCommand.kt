package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.telegram.TrainingReply
import morningpilates.telegram.toSavedTrainingReply
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

private val EMPTY_DIARY_MESSAGE =
    """
    Тренировок пока нет.

    Запиши свою первую тренировку с помощью команды /training_add.
    """.trimIndent()

fun Dispatcher.registerTrainingGetLastCommand(queryService: TrainingQueryService) {
    command("training_get_last") {
        if (message.chat.type != "private") return@command
        val sender = message.from ?: return@command
        val training = queryService.findLatest(TelegramUserId(sender.id)).getOrElse { return@command }
        val reply =
            training?.details?.toSavedTrainingReply(title = "Последняя тренировка")
                ?: TrainingReply(EMPTY_DIARY_MESSAGE)
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = reply.text,
            parseMode = reply.parseMode,
            replyMarkup = reply.replyMarkup,
        )
    }
}
