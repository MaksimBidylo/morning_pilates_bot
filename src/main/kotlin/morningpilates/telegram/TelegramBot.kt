package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.command.registerCancelAllCommand
import morningpilates.telegram.command.registerCancelCommand
import morningpilates.telegram.command.registerHelpCommand
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.training.command.registerTrainingAddCommand
import morningpilates.telegram.training.command.registerTrainingGetLastCommand
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.telegram.training.creation.registerTrainingCreationCallbacks
import morningpilates.telegram.training.creation.registerTrainingCreationTextHandler
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.registerTrainingViewCallbacks
import morningpilates.training.TrainingQueryService

class TelegramBot(
    properties: TelegramProperties,
    flowStorage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    queryService: TrainingQueryService,
) : BotLifecycle {
    private val viewStorage = TrainingViewStorage()
    private val bot =
        bot {
            token = properties.botToken

            dispatch {
                registerStartCommand()
                registerHelpCommand()
                registerTrainingAddCommand(flowStorage, messageSessionStorage, viewStorage)
                registerTrainingGetLastCommand(queryService, viewStorage)
                registerCancelCommand(flowStorage, sessionManager, messageSessionStorage, viewStorage)
                registerCancelAllCommand(flowStorage, messageSessionStorage, viewStorage)
                registerTrainingCreationTextHandler(flowStorage, sessionManager, messageSessionStorage)
                registerTrainingCreationCallbacks(flowStorage, sessionManager, messageSessionStorage)
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
