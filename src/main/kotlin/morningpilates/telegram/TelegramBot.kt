package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.command.registerCancelCommand
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.command.registerTrainingAddCommand
import morningpilates.telegram.command.registerTrainingGetLastCommand
import morningpilates.training.TrainingQueryService

class TelegramBot(
    properties: TelegramProperties,
    flowStorage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
    queryService: TrainingQueryService,
) : BotLifecycle {
    private val bot =
        bot {
            token = properties.botToken

            dispatch {
                registerStartCommand()
                registerTrainingAddCommand(flowStorage, messageSessionStorage)
                registerTrainingGetLastCommand(queryService)
                registerCancelCommand(flowStorage, sessionManager, messageSessionStorage)
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
