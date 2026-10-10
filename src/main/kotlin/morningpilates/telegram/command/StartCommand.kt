package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId

fun Dispatcher.registerStartCommand() {
    command("start") {
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = "Morning Pilates Bot работает 🥋",
        )
    }
}
