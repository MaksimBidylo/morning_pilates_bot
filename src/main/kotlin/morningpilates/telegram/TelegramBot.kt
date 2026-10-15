package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.command.registerCancelAllCommand
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
    private val viewStorage = TrainingViewStorage()
    private val bot =
        bot {
            token = properties.botToken

            dispatch {
                registerStartCommand()
                registerTrainingAddCommand(flowStorage, messageSessionStorage, viewStorage)
                registerTrainingGetLastCommand(queryService, viewStorage)
                registerCancelCommand(flowStorage, sessionManager, messageSessionStorage, viewStorage)
                registerCancelAllCommand(flowStorage, messageSessionStorage, viewStorage)
                registerTrainingTextHandler(flowStorage, sessionManager, messageSessionStorage)
                registerTrainingChoiceCallbacks(flowStorage, sessionManager, messageSessionStorage)
                registerTrainingViewCallbacks(viewStorage, queryService)
            }
        }

    override fun start() {
        bot.startPolling()
    }

    override fun stop() {
        bot.stopPolling()
    }
}
