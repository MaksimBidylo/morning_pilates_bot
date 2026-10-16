package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId

fun Dispatcher.registerStartCommand() {
    command("start") {
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text =
                """
                Morning Pilates Bot 🥋

                Твой личный дневник тренировок по BJJ.

                Записывай тренировки, сохраняй изученные техники и отслеживай свой прогресс.

                Доступные команды:

                /training_add — записать тренировку
                /training_get_last — посмотреть последнюю тренировку
                /training_get_date — найти тренировки за дату
                /help — помощь
                """.trimIndent(),
        )
    }
}
