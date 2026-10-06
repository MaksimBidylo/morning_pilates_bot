package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.telegram.command.registerStartCommand

class TelegramBot(
    private val properties: TelegramProperties,
) {
    private val bot = bot {
        token = properties.botToken

        dispatch {
            registerStartCommand()
        }
    }

    fun start() {
        bot.startPolling()
    }

    fun stop() {
        bot.stopPolling()
    }
}