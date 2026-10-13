package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.command.registerTrainingAddCommand
import morningpilates.telegram.command.registerTrainingCancelCommand

class TelegramBot(
    properties: TelegramProperties,
    flowStorage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
) : BotLifecycle {
    private val bot =
        bot {
            token = properties.botToken

            dispatch {
                registerStartCommand()
                registerTrainingAddCommand(flowStorage, messageSessionStorage)
                registerTrainingCancelCommand(flowStorage, sessionManager, messageSessionStorage)
                registerTrainingTextHandler(flowStorage, sessionManager, messageSessionStorage)
                registerTrainingChoiceCallbacks(flowStorage, sessionManager, messageSessionStorage)
            }
        }

    override fun start() {
        bot.startPolling()
    }

    override fun stop() {
        bot.stopPolling()
    }
}
