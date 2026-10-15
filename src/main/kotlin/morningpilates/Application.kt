package morningpilates

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import morningpilates.bootstrap.configureApplication
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureApplication(
        botFactory = { properties, flowStorage, sessionManager, queryService ->
            TelegramBot(properties, flowStorage, sessionManager, TrainingCreationMessageSessionStorage(), queryService)
        },
    )
}
