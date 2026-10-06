package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId

class TelegramBot(
    private val properties: TelegramProperties,
) {
    private val bot = bot {
        token = properties.botToken

        dispatch {
            command("start") {
                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = "Morning Pilates Bot работает 🥋"
                )
            }
        }
    }

    fun start() {
        bot.startPolling()
    }

    fun stop() {
        bot.stopPolling()
    }
}