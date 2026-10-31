package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.logging.LogLevel
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.profile.ProfileRepository
import morningpilates.telegram.command.registerCancelAllCommand
import morningpilates.telegram.command.registerCancelCommand
import morningpilates.telegram.command.registerHelpCommand
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.logging.TelegramUpdateObserver
import morningpilates.telegram.profile.ProfileInteraction
import morningpilates.telegram.profile.registerProfileHandlers
import morningpilates.telegram.training.command.registerTrainingAddCommand
import morningpilates.telegram.training.command.registerTrainingGetDateCommand
import morningpilates.telegram.training.command.registerTrainingGetLastCommand
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.telegram.training.creation.registerTrainingCreationCallbacks
import morningpilates.telegram.training.creation.registerTrainingCreationTextHandler
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.telegram.training.view.closeTrainingView
import morningpilates.telegram.training.view.registerTrainingDateTextHandler
import morningpilates.telegram.training.view.registerTrainingViewCallbacks
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.slf4j.LoggerFactory

class TelegramBot(
    profileRepository: ProfileRepository,
    properties: TelegramProperties,
    flowStorage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    queryService: TrainingQueryService,
    deleteTraining: (TelegramUserId, TrainingId) -> Result<Boolean>,
) : BotLifecycle {
    private val viewStorage = TrainingViewStorage(properties.trainingSearchDateCalendarEnabled)
    private val profile =
        ProfileInteraction(
            repository = profileRepository,
            diaryBusy = { userId ->
                flowStorage.get(userId) != null || viewStorage.get(userId)?.dateSearch?.isWaitingForDate == true
            },
            closeTrainingView = { bot, userId -> bot.closeTrainingView(userId, viewStorage) },
        )
    private val bot =
        bot {
            token = properties.botToken
            logLevel = LogLevel.None

            dispatch {
                addHandler(TelegramUpdateObserver())
                telegramError {
                    LoggerFactory
                        .getLogger(TelegramBot::class.java)
                        .error("event=telegram_polling_failure operation=getUpdates transport=long_polling")
                }
                registerStartCommand()
                registerHelpCommand()
                registerTrainingAddCommand(flowStorage, messageSessionStorage, viewStorage, profile)
                registerTrainingGetLastCommand(queryService, viewStorage, profile)
                registerTrainingGetDateCommand(flowStorage, messageSessionStorage, viewStorage, profile)
                registerCancelCommand(flowStorage, sessionManager, messageSessionStorage, viewStorage, profile)
                registerCancelAllCommand(flowStorage, messageSessionStorage, viewStorage, profile)
                registerTrainingCreationTextHandler(
                    flowStorage,
                    sessionManager,
                    messageSessionStorage,
                    viewStorage,
                    profile,
                )
                registerTrainingDateTextHandler(queryService, viewStorage)
                registerTrainingCreationCallbacks(flowStorage, sessionManager, messageSessionStorage)
                registerProfileHandlers(profile)
                registerTrainingViewCallbacks(viewStorage, queryService, deleteTraining)
            }
        }

    override fun start() {
        bot.startPolling()
    }

    override fun stop() {
        bot.stopPolling()
    }
}
