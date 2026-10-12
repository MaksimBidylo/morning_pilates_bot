package morningpilates

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import morningpilates.bootstrap.configureApplication
import morningpilates.telegram.TelegramBot

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureApplication(botFactory = ::TelegramBot)
}
