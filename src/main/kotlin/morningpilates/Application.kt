package morningpilates

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import morningpilates.bootstrap.configureApplication
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

@Suppress("TooGenericExceptionCaught")
fun main(args: Array<String>) {
    try {
        EngineMain.main(args)
    } catch (failure: Exception) {
        LoggerFactory.getLogger("morningpilates.Application").error("event=application_failure", failure)
        exitProcess(1)
    }
}

fun Application.module() {
    configureApplication(
        botFactory = { properties, flowStorage, sessionManager, queryService, profileRepository, deleteTraining ->
            TelegramBot(
                profileRepository,
                properties,
                flowStorage,
                sessionManager,
                TrainingCreationMessageSessionStorage(
                    calendarEnabled = properties.trainingDateCalendarEnabled,
                    timePickerEnabled = properties.trainingTimePickerEnabled,
                ),
                queryService,
                deleteTraining = deleteTraining,
            )
        },
    )
}
