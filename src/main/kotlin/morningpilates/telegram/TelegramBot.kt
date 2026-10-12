package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.config.TelegramProperties
import morningpilates.flow.FlowStorage
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.command.registerTrainingAddCommand

class TelegramBot(
    private val properties: TelegramProperties,
    private val flowStorage: FlowStorage,
) : BotLifecycle {
    private val bot =
        bot {
            token = properties.botToken

            dispatch {
                registerStartCommand()
                registerTrainingAddCommand(flowStorage)
            }
        }

    override fun start() {
        bot.startPolling()
    }

    override fun stop() {
        bot.stopPolling()
    }
}
