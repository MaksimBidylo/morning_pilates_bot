package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.config.TelegramProperties
import morningpilates.telegram.command.registerStartCommand

class TelegramBot(
    private val properties: TelegramProperties,
) : BotLifecycle {
    private val bot = bot {
        token = properties.botToken

        dispatch {
            registerStartCommand()
        }
    }

    override fun start() {
        bot.startPolling()
    }

    override fun stop() {
        bot.stopPolling()
    }
}
